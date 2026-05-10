import grpc
import time
from concurrent import futures
import ai_decision_pb2
import ai_decision_pb2_grpc

class InferenceServer(ai_decision_pb2_grpc.AiDecisionServiceServicer):
    def GetDecision(self, request, context):
        state = request.game_state
        context_info = state.decision_context
        
        print(f"--- Received Decision Request ---")
        print(f"Turn: {state.turn_number}, Phase: {state.current_phase}")
        print(f"Prompt: {context_info.prompt}")
        print(f"Available Actions: {len(context_info.available_actions)}")
        
        # Simple strategy: just pick the first available action
        if context_info.available_actions:
            chosen = context_info.available_actions[0]
            print(f"Choosing Action: {chosen.action_id} ({chosen.description})")
            return ai_decision_pb2.DecisionResponse(action_id=chosen.action_id)
        
        print("No actions available!")
        return ai_decision_pb2.DecisionResponse(action_id="")

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    ai_decision_pb2_grpc.add_AiDecisionServiceServicer_to_server(InferenceServer(), server)
    server.add_insecure_port('[::]:50052')
    print("Inference Server starting on port 50052...")
    server.start()
    try:
        while True:
            time.sleep(86400)
    except KeyboardInterrupt:
        server.stop(0)

if __name__ == '__main__':
    serve()
