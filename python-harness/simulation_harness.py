import grpc
import simulation_pb2
import simulation_pb2_grpc

def run_simulation():
    # Connect to Forge Server
    channel = grpc.insecure_channel('localhost:50051')
    stub = simulation_pb2_grpc.MatchSimulationServiceStub(channel)
    
    # Configure the request
    request = simulation_pb2.SimulationRequest(
        deck1_path="forge-gui/res/adventure/Crystal_Kingdoms/decks/starter/Adventure - Angelo Cannon.dck",
        deck2_path="forge-gui/res/adventure/Crystal_Kingdoms/decks/starter/Adventure - Guardian Gladiolus.dck",
        num_matches=1,
        player1_ai=simulation_pb2.AiConfig(
            type=simulation_pb2.AiConfig.AiType.GRPC,
            grpc_endpoint="localhost:50052"
        ),
        player2_ai=simulation_pb2.AiConfig(
            type=simulation_pb2.AiConfig.AiType.HEURISTIC
        ),
        enable_decision_logging=True
    )
    
    print(f"Submitting simulation request for 1 match...")
    print(f"Player 1: GRPC (localhost:50052)")
    print(f"Player 2: HEURISTIC")
    
    try:
        # We'll use the unary call for simplicity in testing
        response = stub.RunSimulation(request)
        
        print("\n--- Simulation Results ---")
        for result in response.results:
            status = "DNF" if result.dnf else "SUCCESS"
            print(f"Match: {result.match_id}")
            print(f"  Status: {status}")
            print(f"  Winner: {result.winner_name}")
            print(f"  Turns: {result.num_turns}")
            print(f"  Reason: {result.win_reason}")
            
    except grpc.RpcError as e:
        print(f"gRPC Error: {e.code()} - {e.details()}")

if __name__ == '__main__':
    run_simulation()
