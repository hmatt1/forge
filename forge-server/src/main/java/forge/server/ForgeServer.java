package forge.server;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.model.FModel;
import forge.proto.MatchResult;
import forge.proto.MatchSimulationServiceGrpc;
import forge.proto.SimulationRequest;
import forge.proto.SimulationResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ForgeServer {

    private final int port;
    private final Server server;
    private static final int THREAD_POOL_SIZE = 16;

    public ForgeServer(int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new MatchSimulationServiceImpl())
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("Forge Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                ForgeServer.this.stop();
                forge.ai.grpc.GrpcAiController.shutdownAll();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String assetsDir = "forge-gui/";
        if (args.length > 0) {
            assetsDir = args[0];
            if (!assetsDir.endsWith("/") && !assetsDir.endsWith("\\")) {
                assetsDir += java.io.File.separator;
            }
        }

        // Initialize Forge Engine
        System.out.println("Initializing Headless GUI with assets from: " + assetsDir);
        final String finalAssetsDir = assetsDir;
        forge.gui.GuiBase.setInterface(new HeadlessGui() {
            @Override public String getAssetsDir() { return finalAssetsDir; }
        });
        
        System.out.println("Initializing Forge Engine...");
        FModel.initialize(null, null);
        System.out.println("Forge Engine initialized.");

        int port = 50051;
        // The first arg was assetsDir, second could be port
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }
        ForgeServer server = new ForgeServer(port);
        server.start();
        server.blockUntilShutdown();
    }

    static class MatchSimulationServiceImpl extends MatchSimulationServiceGrpc.MatchSimulationServiceImplBase {

        private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        private final MatchTelemetryLogger telemetryLogger = new MatchTelemetryLogger("match_analytics.csv");

        @Override
        public void runSimulation(SimulationRequest request, StreamObserver<SimulationResponse> responseObserver) {
            List<MatchResult> results = new ArrayList<>();
            int numMatches = request.getNumMatches();
            
            // For simplicity in the first draft, we run synchronously in the executor
            // but return all results at once for this unary RPC.
            // Better to use the streaming RPC for long-running batches.
            
            for (int i = 0; i < numMatches; i++) {
                results.add(simulateMatch(request, i));
            }
            
            responseObserver.onNext(SimulationResponse.newBuilder().addAllResults(results).build());
            responseObserver.onCompleted();
        }

        @Override
        public void runSimulationStream(SimulationRequest request, StreamObserver<MatchResult> responseObserver) {
            int numMatches = request.getNumMatches();
            java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(numMatches);

            for (int i = 0; i < numMatches; i++) {
                final int matchIdx = i;
                executor.submit(() -> {
                    try {
                        MatchResult result = simulateMatch(request, matchIdx);
                        synchronized (responseObserver) {
                            responseObserver.onNext(result);
                        }
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            synchronized (responseObserver) {
                                responseObserver.onCompleted();
                            }
                        }
                    }
                });
            }
        }

        private MatchResult simulateMatch(SimulationRequest request, int index) {
            try {
                Deck d1 = DeckSerializer.fromFile(new File(request.getDeck1Path()));
                Deck d2 = DeckSerializer.fromFile(new File(request.getDeck2Path()));

                List<RegisteredPlayer> pp = new ArrayList<>();
                pp.add(createPlayer(d1, 0, request.getPlayer1Ai(), request.getEnableDecisionLogging()));
                pp.add(createPlayer(d2, 1, request.getPlayer2Ai(), request.getEnableDecisionLogging()));

                GameRules rules = new GameRules(GameType.Constructed);
                Match match = new Match(rules, pp, "Simulation-" + index);
                
                Game game = match.createGame();
                match.startGame(game);
                
                MatchResult result = MatchResult.newBuilder()
                        .setMatchId("Match-" + index)
                        .setWinnerName(game.getOutcome().getWinningLobbyPlayer().getName())
                        .setNumTurns(game.getPhaseHandler().getTurn())
                        .setWinReason(game.getOutcome().getWinCondition().toString())
                        .build();

                telemetryLogger.logMatch(game, request.getDeck1Path(), request.getDeck2Path(), result);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                MatchResult dnfResult = MatchResult.newBuilder()
                        .setMatchId("Match-" + index)
                        .setDnf(true)
                        .setWinReason("Error: " + e.getMessage())
                        .build();
                // We don't have a game object here if initialization failed, but we can still log if needed
                // For now just return it.
                return dnfResult;
            }
        }

        private RegisteredPlayer createPlayer(Deck d, int index, forge.proto.AiConfig aiConfig, boolean logDecisions) {
            RegisteredPlayer rp = new RegisteredPlayer(d);
            String name = "Ai-" + index + "-" + d.getName();
            
            if (aiConfig.getType() == forge.proto.AiConfig.AiType.GRPC) {
                forge.ai.grpc.LobbyPlayerGrpc lp = new forge.ai.grpc.LobbyPlayerGrpc(name, null, aiConfig.getGrpcEndpoint());
                if (logDecisions) {
                    lp.setDecisionListener(new DecisionTelemetryLogger("decisions_" + name + ".jsonl"));
                }
                rp.setPlayer(lp);
            } else {
                rp.setPlayer(forge.player.GamePlayerUtil.createAiPlayer(name, index));
            }
            return rp;
        }
    }
}
