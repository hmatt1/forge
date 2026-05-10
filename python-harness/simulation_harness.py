import grpc
import argparse
import sys
import simulation_pb2
import simulation_pb2_grpc

def run_simulation(args):
    # Connect to Forge Server
    options = [('grpc.client_idle_timeout_ms', 30000)]
    channel = grpc.insecure_channel(args.forge_server, options=options)
    stub = simulation_pb2_grpc.MatchSimulationServiceStub(channel)
    
    def get_ai_config(ai_str):
        if ai_str.lower() == 'heuristic':
            return simulation_pb2.AiConfig(type=simulation_pb2.AiConfig.AiType.HEURISTIC)
        else:
            return simulation_pb2.AiConfig(
                type=simulation_pb2.AiConfig.AiType.GRPC,
                grpc_endpoint=ai_str
            )

    request = simulation_pb2.SimulationRequest(
        deck1_path=args.deck1,
        deck2_path=args.deck2,
        num_matches=args.count,
        player1_ai=get_ai_config(args.ai1),
        player2_ai=get_ai_config(args.ai2),
        enable_decision_logging=args.log_decisions
    )
    
    print(f"Submitting simulation request for {args.count} match(es)...")
    print(f"Player 1: {args.ai1} (Deck: {args.deck1})")
    print(f"Player 2: {args.ai2} (Deck: {args.deck2})")
    sys.stdout.flush()
    
    try:
        matches_run = 0
        p1_wins = 0
        p2_wins = 0
        dnfs = 0

        for result in stub.RunSimulationStream(request):
            matches_run += 1
            status = "SUCCESS"
            if result.dnf:
                status = "DNF"
                dnfs += 1
            else:
                if "Ai-0" in result.winner_name:
                    p1_wins += 1
                else:
                    p2_wins += 1

            print(f"[{matches_run}/{args.count}] Match: {result.match_id} | Status: {status} | Winner: {result.winner_name} | Turns: {result.num_turns}")
            sys.stdout.flush()
            
            if matches_run >= args.count:
                break
        
        print(f"\n--- Batch Finished (Received all {matches_run} results) ---")
        print("\n--- Final Results ---")
        print(f"Total Matches: {matches_run}")
        print(f"P1 Wins: {p1_wins} ({(p1_wins/matches_run)*100:.1f}%)")
        print(f"P2 Wins: {p2_wins} ({(p2_wins/matches_run)*100:.1f}%)")
        if dnfs > 0:
            print(f"DNFs: {dnfs}")
        sys.stdout.flush()
            
    except grpc.RpcError as e:
        print(f"gRPC Error: {e.code()} - {e.details()}")
        sys.stdout.flush()
    finally:
        channel.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Forge Match Simulation Harness')
    parser.add_argument('--count', type=int, default=1, help='Number of matches to run')
    parser.add_argument('--deck1', type=str, required=True, help='Path to deck 1 (.dck)')
    parser.add_argument('--deck2', type=str, required=True, help='Path to deck 2 (.dck)')
    parser.add_argument('--ai1', type=str, default='localhost:50052', help='AI 1 type ("heuristic" or gRPC endpoint)')
    parser.add_argument('--ai2', type=str, default='heuristic', help='AI 2 type ("heuristic" or gRPC endpoint)')
    parser.add_argument('--forge-server', type=str, default='localhost:50051', help='Forge Server endpoint')
    parser.add_argument('--log-decisions', action='store_true', help='Enable high-fidelity decision logging')
    
    args = parser.parse_args()
    run_simulation(args)
