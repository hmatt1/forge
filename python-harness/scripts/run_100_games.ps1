# Run a benchmark of 100 matches between the GRPC AI and Heuristic AI
# Requirement: Forge Server and Inference Server must be running

$deck1 = "forge-gui/res/adventure/Crystal_Kingdoms/decks/starter/Adventure - Angelo Cannon.dck"
$deck2 = "forge-gui/res/adventure/Crystal_Kingdoms/decks/starter/Adventure - Guardian Gladiolus.dck"

python python-harness/simulation_harness.py --count 100 --deck1 $deck1 --deck2 $deck2 --ai1 localhost:50052 --ai2 heuristic
