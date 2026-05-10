package forge.ai.grpc;

import forge.ai.AiControllerAbstract;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GrpcAiController extends AiControllerAbstract {

    private final ManagedChannel channel;
    private final AiDecisionServiceGrpc.AiDecisionServiceBlockingStub blockingStub;

    public GrpcAiController(Player computerPlayer, Game game, String grpcEndpoint) {
        super(computerPlayer, game);
        this.channel = ManagedChannelBuilder.forTarget(grpcEndpoint)
                .usePlaintext()
                .build();
        this.blockingStub = AiDecisionServiceGrpc.newBlockingStub(channel);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private String makeDecision(String prompt, List<Action> actions) {
        GameState state = ProtoMapper.mapGameState(game, player, prompt, actions);
        try {
            DecisionResponse response = blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getDecision(DecisionRequest.newBuilder().setGameState(state).build());
            String actionId = response.getActionId();
            notifyDecision(state, actionId);
            return actionId;
        } catch (Exception e) {
            System.err.println("gRPC AI Error: " + e.getMessage());
            // TODO: Signal DNF as per plan
            throw new RuntimeException("gRPC AI failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) return null;
        landList = ComputerUtilCard.dedupeCards(landList);
        if (landList.size() == 1) return landList.get(0);

        List<Action> actions = new ArrayList<>();
        for (Card c : landList) {
            actions.add(Action.newBuilder()
                    .setActionId(String.valueOf(c.getId()))
                    .setDescription("Play " + c.getName())
                    .setCardId(String.valueOf(c.getId()))
                    .build());
        }

        String actionId = makeDecision("Choose a land to play", actions);
        for (Card c : landList) {
            if (String.valueOf(c.getId()).equals(actionId)) {
                return c;
            }
        }
        return super.chooseBestLandToPlay(landList); // Fallback if actionId not found
    }

    @Override
    public SpellAbility chooseSpellAbilityToPlayFromList(List<SpellAbility> all, boolean skipCounter) throws Exception {
        if (all == null || all.isEmpty()) return null;

        List<SpellAbility> playable = new ArrayList<>();
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            if (skipCounter && sa.getApi() == forge.game.ability.ApiType.Counter) continue;
            if (sa.canPlay()) playable.add(sa);
        }

        if (playable.isEmpty()) return null;

        List<Action> actions = new ArrayList<>();
        actions.add(Action.newBuilder()
                .setActionId("PASS")
                .setDescription("Pass priority")
                .build());

        for (int i = 0; i < playable.size(); i++) {
            SpellAbility sa = playable.get(i);
            actions.add(Action.newBuilder()
                    .setActionId("SA_" + i)
                    .setDescription(sa.getDescription())
                    .setSpellAbilityId(String.valueOf(sa.hashCode()))
                    .build());
        }

        String actionId = makeDecision("Choose a spell or ability to play", actions);
        if ("PASS".equals(actionId)) return null;

        if (actionId.startsWith("SA_")) {
            int idx = Integer.parseInt(actionId.substring(3));
            if (idx >= 0 && idx < playable.size()) {
                return playable.get(idx);
            }
        }

        return null;
    }
}
