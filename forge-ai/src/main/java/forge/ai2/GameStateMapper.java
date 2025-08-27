package forge.ai2;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;

import java.util.*;
import java.util.stream.Collectors;

public class GameStateMapper {

    /**
     * Convert Game object to focused DTO from the perspective of the decision-making player
     */
    public static GameStateDto mapGameState(Game game, Player perspective) {
        GameStateDto dto = new GameStateDto();

        // Basic game state
        dto.turnNumber = game.getPhaseHandler().getTurn();
        dto.currentPhase = game.getPhaseHandler().getPhase().toString();
        dto.currentStep = game.getPhaseHandler().getPhase().toString(); // May need refinement
        dto.activePlayer = game.getPhaseHandler().getPlayerTurn().getName();
        dto.priorityPlayer = game.getPhaseHandler().getPriorityPlayer() != null ?
                game.getPhaseHandler().getPriorityPlayer().getName() : null;
        dto.isMyTurn = game.getPhaseHandler().getPlayerTurn().equals(perspective);
        dto.doIHavePriority = perspective.equals(game.getPhaseHandler().getPriorityPlayer());
        dto.myPlayerId = perspective.getName();

        // Special game states
        dto.monarch = game.getMonarch() != null ? game.getMonarch().getName() : null;
        dto.initiative = game.getHasInitiative() != null ? game.getHasInitiative().getName() : null;
        dto.isDayTime = game.getDayTime();

        // Map all players
        dto.players = new ArrayList<>();
        for (Player player : game.getPlayersInTurnOrder()) {
            dto.players.add(mapPlayerState(game, player, perspective));
        }

        // Map stack
        dto.stack = mapStack(game);

        // Map combat state
        dto.combat = mapCombatState(game);

        // Map recent history
        dto.recentHistory = mapRecentHistory(game);

        // Decision context would be set separately based on what the AI is being asked to do
        dto.decisionContext = null;

        return dto;
    }

    private static GameStateDto.PlayerState mapPlayerState(Game game, Player player, Player perspective) {
        GameStateDto.PlayerState state = new GameStateDto.PlayerState();

        state.playerId = player.getName();
        state.name = player.getName();
        state.life = player.getLife();
        state.isAlive = !player.hasLost();

        // Hand information
        state.handSize = player.getZone(ZoneType.Hand).size();
        if (player.equals(perspective)) {
            // I can see my own hand
            state.myHand = player.getCardsIn(ZoneType.Hand).stream()
                    .map(card -> mapCardState(card, game))
                    .collect(Collectors.toList());
        } else {
            state.myHand = null;
            // TODO: Add known opponent cards (from effects that revealed them)
            state.knownOpponentCards = new ArrayList<>();
        }

        // Library information
        state.librarySize = player.getZone(ZoneType.Library).size();
        state.knownLibraryTop = new ArrayList<>(); // TODO: Implement scry/peek tracking
        state.knownLibraryCards = new ArrayList<>();

        // Visible zones
        state.battlefield = player.getCardsIn(ZoneType.Battlefield).stream()
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        state.graveyard = player.getCardsIn(ZoneType.Graveyard).stream()
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        state.exile = player.getCardsIn(ZoneType.Exile).stream()
                .filter(card -> !card.isFaceDown()) // Only face-up exiled cards are visible
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        state.command = player.getCardsIn(ZoneType.Command).stream()
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        // Resources
        state.manaPool = mapManaPool(player.getManaPool());
        state.availableManaThisTurn = calculateAvailableMana(player);
        state.timesLandsPlayedThisTurn = player.getLandsPlayedThisTurn();

        // Status effects
        state.isBeingAttacked = game.getCombat() == null || game.getCombat().isPlayerAttacked(player);
        state.hasProtectionFrom = false; // Simplified
        state.statusEffects = extractPlayerStatusEffects(player);

        return state;
    }

    private static GameStateDto.CardState mapCardState(Card card, Game game) {
        GameStateDto.CardState state = new GameStateDto.CardState();

        state.name = card.getName();
        state.manaCost = card.getManaCost() != null ? card.getManaCost().toString() : "";
        state.colors = card.getColor().toString();
        state.types = card.getType().toString();
        state.supertypes =  card.getType().getSupertypes().toString();

        // Creature stats
        if (card.isCreature()) {
            state.power = card.getNetPower();
            state.toughness = card.getNetToughness();
            state.basePower = card.getBasePower();
            state.baseToughness = card.getBaseToughness();
        }

        state.keywords = card.getKeywords().stream().map(r -> r.getKeyword().toString()).toList();

        // State
        state.tapped = card.isTapped();
        state.summingSick = card.hasSickness();
        state.transformed = card.isTransformed();
        state.currentState = card.getCurrentStateName().toString();
        state.faceDown = card.isFaceDown();
        state.faceDownType = determineFaceDownType(card);

        // Modifications
        state.counters = new HashMap<>();
        for (CounterType counterType : card.getCounters().keySet()) {
            state.counters.put(counterType.toString(), card.getCounters(counterType));
        }

        state.attachedTo = card.getAttachedTo() != null ?
                Arrays.asList(card.getAttachedTo().getName()) : new ArrayList<>();
        state.attachments = card.getAttachedCards().stream()
                .map(Card::getName)
                .collect(Collectors.toList());

        // Combat state
        Combat combat = game.getCombat();
        if (combat != null) {
            state.attacking = combat.isAttacking(card);
            state.attackingPlayer = combat.getDefendingPlayerRelatedTo(card) != null ?
                    combat.getDefendingPlayerRelatedTo(card).getName() : null;
            state.blocking = combat.isBlocking(card);
            state.blockingCreatures = combat.getBlockers(card).stream()
                    .map(Card::getName)
                    .collect(Collectors.toList());
        } else {
            state.attacking = false;
            state.blocking = false;
            state.blockingCreatures = new ArrayList<>();
        }

        // Zone information
        state.zone = card.getZone() != null ? card.getZone().getZoneType().toString() : "unknown";
        state.owner = card.getOwner().getName();
        state.controller = card.getController().getName();
        state.canActivateAbilities = true; // Simplified - would need more complex logic

        // Abilities
        state.abilities = card.getSpellAbilities().stream()
                .map(GameStateMapper::mapAbilityState)
                .collect(Collectors.toList());

        return state;
    }

    private static GameStateDto.AbilityState mapAbilityState(SpellAbility ability) {
        GameStateDto.AbilityState state = new GameStateDto.AbilityState();

        state.description = ability.getDescription();
        state.manaCost = ability.getPayCosts() != null ? ability.getPayCosts().toString() : "";
        state.type = ability.isSpell() ? "spell" : ability.isTrigger() ? "triggered" : "activated";
        state.canActivateNow = ability.canPlay(); // Simplified
        state.timesActivatedThisTurn = ability.getActivationsThisTurn();
        state.timesResolvedThisTurn = ability.getResolvedThisTurn();

        return state;
    }

    private static List<GameStateDto.StackObject> mapStack(Game game) {
        List<GameStateDto.StackObject> stackObjects = new ArrayList<>();

        // Iterate through stack in order (top to bottom)
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            GameStateDto.StackObject obj = new GameStateDto.StackObject();

            SpellAbility sa = stackInstance.getSpellAbility();
            Card sourceCard = sa.getHostCard();

            // Basic information
            obj.name = sourceCard.getName();
            obj.type = stackInstance.isSpell() ? "spell" : "ability";
            obj.controller = stackInstance.getActivatingPlayer().getName();
            obj.description = stackInstance.getStackDescription();

            // Determine if it can be countered
            obj.canBeCountered = true; // Default assumption
            if (sa.isSpell()) {
                // Check for uncounterable keyword or abilities
                obj.canBeCountered = !sourceCard.hasKeyword("Uncounterable") &&
                        !sourceCard.hasKeyword("Split second") &&
                        !sa.hasParam("UnCounterable");
            } else {
                // Abilities generally can't be countered unless specifically stated
                obj.canBeCountered = sa.hasParam("Counterable");
            }

            // Converted mana cost (for spells)
            if (stackInstance.isSpell()) {
                obj.convertedManaCost = sourceCard.getCMC();
            } else {
                obj.convertedManaCost = 0;
            }

            // Extract targets
            obj.targets = new ArrayList<>();
            if (sa.usesTargeting()) {
                for (GameObject target : sa.getTargets()) {
                    if (target instanceof Card) {
                        obj.targets.add(((Card) target).getName());
                    } else if (target instanceof Player) {
                        obj.targets.add(((Player) target).getName());
                    } else if (target instanceof SpellAbility) {
                        obj.targets.add(((SpellAbility) target).getHostCard().getName() + " (ability)");
                    } else {
                        obj.targets.add(target.toString());
                    }
                }
            }

            stackObjects.add(obj);
        }

        return stackObjects;
    }

    private static GameStateDto.CombatState mapCombatState(Game game) {
        Combat combat = game.getCombat();
        if (combat == null) {
            return null;
        }

        GameStateDto.CombatState state = new GameStateDto.CombatState();

        state.phase = game.getPhaseHandler().getPhase().toString();

        state.attackers = combat.getAttackers().stream().map(attacker -> {
            GameStateDto.AttackingCreature attacking = new GameStateDto.AttackingCreature();
            attacking.cardName = attacker.getName();
            attacking.attackingPlayer = combat.getDefendingPlayerRelatedTo(attacker) != null ?
                    combat.getDefendingPlayerRelatedTo(attacker).getName() : "";
            attacking.isBlocked = combat.isBlocked(attacker);
            attacking.blockedBy = combat.getBlockers(attacker).stream()
                    .map(Card::getName)
                    .collect(Collectors.toList());
            return attacking;
        }).collect(Collectors.toList());

        state.blocks = new ArrayList<>(); // TODO: Implement blocking assignments

        PhaseType currentPhase = game.getPhaseHandler().getPhase();
        state.canStillDeclareAttackers = currentPhase == PhaseType.COMBAT_DECLARE_ATTACKERS;
        state.canStillDeclareBlockers = currentPhase == PhaseType.COMBAT_DECLARE_BLOCKERS;

        return state;
    }

    private static GameStateDto.RecentHistory mapRecentHistory(Game game) {
        GameStateDto.RecentHistory history = new GameStateDto.RecentHistory();

        // These would require tracking throughout the turn
        history.spellsCastThisTurn = new ArrayList<>();
        history.creaturesEnteredThisTurn = new ArrayList<>();
        history.creaturesLeavingBattlefield = new ArrayList<>();
        history.damageTakenThisTurn = new HashMap<>();
        history.landsPlayedThisTurn = new ArrayList<>();

        return history;
    }

    private static List<String> mapManaPool(ManaPool pool) {
        List<String> mana = new ArrayList<>();

        for (int i = 0; i < pool.getAmountOfColor((byte) 1); i++) mana.add("W"); // White
        for (int i = 0; i < pool.getAmountOfColor((byte) 2); i++) mana.add("U"); // Blue
        for (int i = 0; i < pool.getAmountOfColor((byte) 4); i++) mana.add("B"); // Black
        for (int i = 0; i < pool.getAmountOfColor((byte) 8); i++) mana.add("R"); // Red
        for (int i = 0; i < pool.getAmountOfColor((byte) 16); i++) mana.add("G"); // Green
        for (int i = 0; i < pool.getAmountOfColor((byte) 32); i++) mana.add("C"); // Colorless

        return mana;
    }

    private static Map<String, Integer> calculateAvailableMana(Player player) {
        Map<String, Integer> available = new HashMap<>();

        // Count untapped lands and mana-producing artifacts
        for (Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            if (!permanent.isTapped() && (permanent.isLand() || permanent.hasSVar("AddedMana"))) {
                // Simplified - would need to parse mana abilities properly
                if (permanent.isBasicLand()) {
                    String manaType = getBasicLandManaType(permanent.getName());
                    available.merge(manaType, 1, Integer::sum);
                }
            }
        }

        return available;
    }

    private static String getBasicLandManaType(String landName) {
        switch (landName.toLowerCase()) {
            case "plains": return "W";
            case "island": return "U";
            case "swamp": return "B";
            case "mountain": return "R";
            case "forest": return "G";
            default: return "C";
        }
    }

    private static String determineFaceDownType(Card card) {
        if (!card.isFaceDown()) return null;

        if (card.isManifested()) return "manifest";
        if (card.isFaceDown()) return "facedown";
        if (card.hasKeyword("Foretell")) return "foretell";

        return "unknown";
    }

    private static List<String> extractPlayerStatusEffects(Player player) {
        List<String> effects = new ArrayList<>();

        if (player.hasKeyword("Hexproof")) effects.add("hexproof");
        if (player.hasKeyword("Shroud")) effects.add("shroud");
        if (player.cantLose()) effects.add("cannot_lose");
        if (player.cantWin()) effects.add("cannot_win");

        return effects;
    }
}