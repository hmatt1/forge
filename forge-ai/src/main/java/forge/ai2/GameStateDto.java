
package forge.ai2;

import java.util.List;
import java.util.Map;

/**
 * Complete game state representation for MTG decision-making from a specific player's perspective
 */
public class GameStateDto {

    // Game timing and control
    public int turnNumber;
    public String currentPhase; // "UNTAP", "UPKEEP", "DRAW", "MAIN1", "BEGIN_COMBAT", etc.
    public String currentStep;
    public String activePlayer; // whose turn it is
    public String priorityPlayer; // who can act right now
    public boolean isMyTurn;
    public boolean doIHavePriority;

    // Players in turn order
    public List<PlayerState> players;
    public String myPlayerId;

    // Stack state (spells/abilities resolving)
    public List<StackObject> stack;

    // Combat state (if in combat)
    public CombatState combat;

    // Special game states
    public String monarch; // player ID who is the monarch (if any)
    public String initiative; // player ID who has the initiative (if any)
    public Boolean isDayTime; // true = day, false = night, null = neither

    public static class PlayerState {
        public String playerId;
        public String name;
        public int life;
        public boolean isAlive;

        // Hand information
        public int handSize;
        public List<CardState> myHand; // only populated if this is me
        public List<CardState> knownOpponentCards; // cards I've seen in their hand

        // Library information
        public int librarySize;
        public List<CardState> knownLibraryTop; // cards I know are on top
        public List<CardState> knownLibraryCards; // cards I know are somewhere in library

        // Visible zones
        public List<CardState> battlefield;
        public List<CardState> graveyard;
        public List<CardState> exile; // face-up exiled cards
        public List<CardState> command; // commanders, emblems, etc.

        // Resources
        public List<String> manaPool; // current floating mana ["W", "U", "B", "R", "G", "C"]
        public Map<String, Integer> availableManaThisTurn; // lands that can still tap, etc.
        public int timesLandsPlayedThisTurn; // can still play a land this turn

        // Status effects
        public boolean isBeingAttacked;
        public boolean hasProtectionFrom; // simplified - could be expanded
        public List<String> statusEffects; // "hexproof", "shroud", "can't be countered", etc.
    }

    public static class CardState {
        public String name;
        public String manaCost;
        public String colors;
        public String types;
        public String supertypes;

        // Creature stats
        public Integer power;
        public Integer toughness;
        public Integer basePower; // before modifications
        public Integer baseToughness;
        public List<String> keywords; // ["flying", "trample", "hexproof"]

        // State
        public boolean tapped;
        public boolean summingSick;
        public boolean transformed;
        public String currentState; // for double-faced cards
        public boolean faceDown;
        public String faceDownType; // "morph", "manifest", "foretell"

        // Modifications
        public Map<String, Integer> counters; // "+1/+1" -> 3, "loyalty" -> 4
        public List<String> attachedTo; // names of cards this is attached to
        public List<String> attachments; // names of cards attached to this
        public List<String> tempAbilities; // granted by other effects
        public List<String> lostAbilities; // removed by other effects

        // Combat state
        public boolean attacking;
        public String attackingPlayer; // who this creature is attacking (if attacking)
        public boolean blocking;
        public List<String> blockingCreatures; // creatures this is blocking
        public boolean hasDealtDamage; // this turn

        // Zone information
        public String zone; // "battlefield", "hand", "graveyard", etc.
        public String owner;
        public String controller;
        public boolean canActivateAbilities;

        // Known abilities
        public List<AbilityState> abilities;
    }

    public static class AbilityState {
        public String description;
        public String manaCost;
        public String type; // "activated", "triggered", "static"
        public boolean canActivateNow;
        public int timesActivatedThisTurn;
        public int timesResolvedThisTurn; // -1 for unlimited
    }

    public static class StackObject {
        public String name;
        public String type; // "spell", "ability"
        public String controller;
        public List<String> targets;
        public String description;
        public boolean canBeCountered;
        public int convertedManaCost; // for spells
    }

    public static class CombatState {
        public String phase; // "BEGIN_COMBAT", "DECLARE_ATTACKERS", "DECLARE_BLOCKERS", "DAMAGE", "END_COMBAT"
        public List<AttackingCreature> attackers;
        public List<BlockingAssignment> blocks;
        public boolean canStillDeclareAttackers;
        public boolean canStillDeclareBlockers;
    }

    public static class AttackingCreature {
        public String cardName;
        public String attackingPlayer; // player being attacked
        public boolean isBlocked;
        public List<String> blockedBy; // creature names
    }

    public static class BlockingAssignment {
        public String blocker;
        public String blocked; // attacker being blocked
        public int damageOrder; // if multiple blockers
    }

    // Game history that might be relevant
    public static class RecentHistory {
        public List<String> spellsCastThisTurn;
        public List<String> creaturesEnteredThisTurn;
        public List<String> creaturesLeavingBattlefield;
        public Map<String, Integer> damageTakenThisTurn; // player -> damage amount
        public List<String> landsPlayedThisTurn;
    }

    public RecentHistory recentHistory;

    // Metadata for AI decision-making
    public static class DecisionContext {
        public List<String> legalActions; // what can I do right now?
        public String prompt; // "Choose a card to play" or "Choose targets for Lightning Bolt"
        public boolean mustAct; // true if passing is not an option
        public int timeoutSeconds;
    }

    public DecisionContext decisionContext;
}