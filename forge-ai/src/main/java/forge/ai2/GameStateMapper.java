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
     * PRIORITY: Create a context-optimized game state that puts constraints FIRST
     * This method frontloads available spell abilities to help LLM focus on valid choices
     */
    public static GameStateDto mapGameStateWithSpellAbilities(Game game, Player perspective, List<SpellAbility> availableSpellAbilities) {
        GameStateDto dto = new GameStateDto();

        // FIRST: What the AI must choose from - put this at the very beginning
        dto.availableSpellAbilities = mapAvailableSpellAbilities(availableSpellAbilities);

        // SECOND: Critical game state for decision making
        dto.turnNumber = game.getPhaseHandler().getTurn();
        dto.currentPhase = game.getPhaseHandler().getPhase().toString();
        dto.activePlayer = game.getPhaseHandler().getPlayerTurn().getName();
        dto.priorityPlayer = game.getPhaseHandler().getPriorityPlayer() != null ?
                game.getPhaseHandler().getPriorityPlayer().getName() : null;
        dto.isMyTurn = game.getPhaseHandler().getPlayerTurn().equals(perspective);
        dto.doIHavePriority = perspective.equals(game.getPhaseHandler().getPriorityPlayer());
        dto.myPlayerId = perspective.getName();

        // THIRD: Essential player states only (minimal context)
        dto.players = new ArrayList<>();
        for (Player player : game.getPlayersInTurnOrder()) {
            dto.players.add(mapEssentialPlayerState(game, player, perspective));
        }

        // FOURTH: Only include stack if it exists and matters
        if (!game.getStack().isEmpty()) {
            dto.stack = mapStack(game);
        } else {
            dto.stack = new ArrayList<>();
        }

        // FIFTH: Combat state if in combat
        dto.combat = game.getCombat() != null ? mapCombatState(game) : null;

        // Minimal recent history
        dto.recentHistory = mapMinimalHistory(game);

        return dto;
    }

    /**
     * Original full game state mapping - use for non-spell-ability decisions
     */
    public static GameStateDto mapGameState(Game game, Player perspective) {
        GameStateDto dto = new GameStateDto();

        // Basic game state
        dto.turnNumber = game.getPhaseHandler().getTurn();
        dto.currentPhase = game.getPhaseHandler().getPhase().toString();
        dto.currentStep = game.getPhaseHandler().getPhase().toString();
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

        // Map all players with full detail
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

        dto.decisionContext = null;
        return dto;
    }

    /**
     * Map available spell abilities with clean, focused descriptions
     */
    private static List<GameStateDto.SpellAbilityContext> mapAvailableSpellAbilities(List<SpellAbility> availableSpellAbilities) {
        List<GameStateDto.SpellAbilityContext> contexts = new ArrayList<>();

        for (SpellAbility sa : availableSpellAbilities) {
            GameStateDto.SpellAbilityContext context = new GameStateDto.SpellAbilityContext();
            context.description = sa.getDescription();
            context.manaCost = sa.getPayCosts() != null ? sa.getPayCosts().toString() : "";
            context.isInstantSpeed = sa.getRestrictions() != null ? sa.getRestrictions().isInstantSpeed() : true;
            context.api = sa.getApi() != null ? sa.getApi().toString() : "Unknown";
            context.isSpell = sa.isSpell();
            context.isAbility = sa.isAbility();
            context.hostCardName = sa.getHostCard() != null ? sa.getHostCard().getName() : "";

            if (sa.usesTargeting()) {
                context.usesTargeting = true;
                context.minTargets = sa.getMinTargets();
                context.maxTargets = sa.getMaxTargets();
            }

            contexts.add(context);
        }

        return contexts;
    }

    /**
     * Essential player state - only what's needed for spell/ability decisions
     */
    private static GameStateDto.PlayerState mapEssentialPlayerState(Game game, Player player, Player perspective) {
        GameStateDto.PlayerState state = new GameStateDto.PlayerState();

        state.playerId = player.getName();
        state.name = player.getName();
        state.life = player.getLife();
        state.isAlive = !player.hasLost();
        state.handSize = player.getZone(ZoneType.Hand).size();

        // Hand - only for the decision-making player
        if (player.equals(perspective)) {
            state.myHand = player.getCardsIn(ZoneType.Hand).stream()
                    .map(card -> mapEssentialCardState(card, game))
                    .collect(Collectors.toList());
        } else {
            state.myHand = null;
            state.knownOpponentCards = new ArrayList<>();
        }

        // Only threats and blockers on battlefield
        state.battlefield = player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> card.isCreature() || card.isPlaneswalker() || card.hasKeyword("Vigilance"))
                .map(card -> mapEssentialCardState(card, game))
                .collect(Collectors.toList());

        // Essential graveyard (only recently relevant cards)
        state.graveyard = player.getCardsIn(ZoneType.Graveyard).stream()
                .limit(5) // Only most recent 5 cards
                .map(card -> mapEssentialCardState(card, game))
                .collect(Collectors.toList());

        // Minimal exile info
        state.exile = player.getCardsIn(ZoneType.Exile).stream()
                .filter(card -> !card.isFaceDown())
                .limit(3)
                .map(card -> mapEssentialCardState(card, game))
                .collect(Collectors.toList());

        // Command zone
        state.command = player.getCardsIn(ZoneType.Command).stream()
                .map(card -> mapEssentialCardState(card, game))
                .collect(Collectors.toList());

        // Mana resources
        state.manaPool = mapManaPool(player.getManaPool());
        state.availableManaThisTurn = calculateAvailableMana(player);
        state.timesLandsPlayedThisTurn = player.getLandsPlayedThisTurn();

        // Combat status
        state.isBeingAttacked = game.getCombat() != null && game.getCombat().isPlayerAttacked(player);
        state.hasProtectionFrom = false;
        state.statusEffects = extractEssentialStatusEffects(player);

        return state;
    }

    /**
     * Full player state mapping - use for non-spell-ability decisions
     */
    private static GameStateDto.PlayerState mapPlayerState(Game game, Player player, Player perspective) {
        GameStateDto.PlayerState state = new GameStateDto.PlayerState();

        state.playerId = player.getName();
        state.name = player.getName();
        state.life = player.getLife();
        state.isAlive = !player.hasLost();

        // Hand information
        state.handSize = player.getZone(ZoneType.Hand).size();
        if (player.equals(perspective)) {
            state.myHand = player.getCardsIn(ZoneType.Hand).stream()
                    .map(card -> mapCardState(card, game))
                    .collect(Collectors.toList());
        } else {
            state.myHand = null;
            state.knownOpponentCards = new ArrayList<>();
        }

        // Library information
        state.librarySize = player.getZone(ZoneType.Library).size();
        state.knownLibraryTop = new ArrayList<>();
        state.knownLibraryCards = new ArrayList<>();

        // Visible zones
        state.battlefield = player.getCardsIn(ZoneType.Battlefield).stream()
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        state.graveyard = player.getCardsIn(ZoneType.Graveyard).stream()
                .map(card -> mapCardState(card, game))
                .collect(Collectors.toList());

        state.exile = player.getCardsIn(ZoneType.Exile).stream()
                .filter(card -> !card.isFaceDown())
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
        state.hasProtectionFrom = false;
        state.statusEffects = extractPlayerStatusEffects(player);

        return state;
    }

    /**
     * Essential card state - only critical info for decision making
     */
    private static GameStateDto.CardState mapEssentialCardState(Card card, Game game) {
        GameStateDto.CardState state = new GameStateDto.CardState();

        state.name = card.getName();
        state.manaCost = card.getManaCost() != null ? card.getManaCost().toString() : "no cost";
        state.colors = card.getColor().toString();
        state.types = card.getType().toString();
        state.supertypes = card.getType().getSupertypes().toString();

        // Creature stats
        if (card.isCreature()) {
            state.power = card.getNetPower();
            state.toughness = card.getNetToughness();
            state.basePower = card.getBasePower();
            state.baseToughness = card.getBaseToughness();
        }

        // Only essential keywords
        state.keywords = card.getKeywords().stream()
                .map(r -> r.getKeyword().toString())
                .filter(k -> isEssentialKeyword(k))
                .collect(Collectors.toList());

        // Essential state
        state.tapped = card.isTapped();
        state.summingSick = card.hasSickness();
        state.transformed = card.isTransformed();
        state.currentState = card.getCurrentStateName().toString();
        state.faceDown = card.isFaceDown();
        state.faceDownType = determineFaceDownType(card);

        // Only important counters
        state.counters = new HashMap<>();
        for (CounterType counterType : card.getCounters().keySet()) {
            int count = card.getCounters(counterType);
            if (count > 0) {
                state.counters.put(counterType.toString(), count);
            }
        }

        // Attachments
        state.attachedTo = card.getAttachedTo() != null ?
                Arrays.asList(card.getAttachedTo().getName()) : new ArrayList<>();
        state.attachments = card.getAttachedCards().stream()
                .map(Card::getName)
                .collect(Collectors.toList());

        // Skip temp abilities for essential mapping
        state.tempAbilities = null;
        state.lostAbilities = null;

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
            state.attackingPlayer = null;
            state.blocking = false;
            state.blockingCreatures = new ArrayList<>();
        }
        state.hasDealtDamage = false; // Simplified

        // Zone information
        state.zone = card.getZone() != null ? card.getZone().getZoneType().toString() : "unknown";
        state.owner = card.getOwner().getName();
        state.controller = card.getController().getName();
        state.canActivateAbilities = true;

        // Only essential abilities
        state.abilities = card.getSpellAbilities().stream()
                .filter(ability -> isEssentialAbility(ability))
                .map(GameStateMapper::mapAbilityState)
                .collect(Collectors.toList());

        return state;
    }

    /**
     * Full card state mapping - use for detailed analysis
     */
    private static GameStateDto.CardState mapCardState(Card card, Game game) {
        GameStateDto.CardState state = new GameStateDto.CardState();

        state.name = card.getName();
        state.manaCost = card.getManaCost() != null ? card.getManaCost().toString() : "no cost";
        state.colors = card.getColor().toString();
        state.types = card.getType().toString();
        state.supertypes = card.getType().getSupertypes().toString();

        // Creature stats
        if (card.isCreature()) {
            state.power = card.getNetPower();
            state.toughness = card.getNetToughness();
            state.basePower = card.getBasePower();
            state.baseToughness = card.getBaseToughness();
        }

        state.keywords = card.getKeywords().stream()
                .map(r -> r.getKeyword().toString())
                .collect(Collectors.toList());

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

        state.tempAbilities = null; // TODO: Track temporary abilities
        state.lostAbilities = null; // TODO: Track lost abilities

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
        state.hasDealtDamage = false; // TODO: Track damage dealt this turn

        // Zone information
        state.zone = card.getZone() != null ? card.getZone().getZoneType().toString() : "unknown";
        state.owner = card.getOwner().getName();
        state.controller = card.getController().getName();
        state.canActivateAbilities = true; // Simplified

        // Abilities
        state.abilities = card.getSpellAbilities().stream()
                .map(GameStateMapper::mapAbilityState)
                .collect(Collectors.toList());

        return state;
    }

    private static GameStateDto.AbilityState mapAbilityState(SpellAbility ability) {
        GameStateDto.AbilityState state = new GameStateDto.AbilityState();

        state.description = ability.getDescription();
        state.manaCost = ability.getPayCosts() != null ? ability.getPayCosts().toString() : "no cost";
        state.type = ability.isSpell() ? "spell" : ability.isTrigger() ? "triggered" : "activated";
        state.canActivateNow = ability.canPlay();
        state.timesActivatedThisTurn = ability.getActivationsThisTurn();
        state.timesResolvedThisTurn = ability.getResolvedThisTurn();

        return state;
    }

    private static List<GameStateDto.StackObject> mapStack(Game game) {
        List<GameStateDto.StackObject> stackObjects = new ArrayList<>();

        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            GameStateDto.StackObject obj = new GameStateDto.StackObject();

            SpellAbility sa = stackInstance.getSpellAbility();
            Card sourceCard = sa.getHostCard();

            obj.name = sourceCard.getName();
            obj.type = stackInstance.isSpell() ? "spell" : "ability";
            obj.controller = stackInstance.getActivatingPlayer().getName();

            // Countering info
            obj.canBeCountered = true;
            if (sa.isSpell()) {
                obj.canBeCountered = !sourceCard.hasKeyword("Uncounterable") &&
                        !sourceCard.hasKeyword("Split second") &&
                        !sa.hasParam("UnCounterable");
            } else {
                obj.canBeCountered = sa.hasParam("Counterable");
            }

            // Mana cost
            if (stackInstance.isSpell()) {
                obj.convertedManaCost = sourceCard.getCMC();
            } else {
                obj.convertedManaCost = 0;
            }

            // Targets
            obj.targets = new ArrayList<>();
            if (sa.usesTargeting()) {
                for (GameObject target : sa.getTargets()) {
                    if (target instanceof Card) {
                        obj.targets.add(((Card) target).getName());
                    } else if (target instanceof Player) {
                        obj.targets.add(((Player) target).getName());
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

        state.blocks = new ArrayList<>();
        PhaseType currentPhase = game.getPhaseHandler().getPhase();
        state.canStillDeclareAttackers = currentPhase == PhaseType.COMBAT_DECLARE_ATTACKERS;
        state.canStillDeclareBlockers = currentPhase == PhaseType.COMBAT_DECLARE_BLOCKERS;

        return state;
    }

    private static GameStateDto.RecentHistory mapMinimalHistory(Game game) {
        GameStateDto.RecentHistory history = new GameStateDto.RecentHistory();

        // Only track what's immediately relevant
        history.spellsCastThisTurn = new ArrayList<>();
        history.creaturesEnteredThisTurn = new ArrayList<>();
        history.creaturesLeavingBattlefield = new ArrayList<>();
        history.damageTakenThisTurn = new HashMap<>();
        history.landsPlayedThisTurn = new ArrayList<>();

        return history;
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

    // Helper methods
    private static boolean isEssentialKeyword(String keyword) {
        Set<String> essential = Set.of("Flying", "Trample", "Lifelink", "Deathtouch", "Haste",
                "Vigilance", "First strike", "Double strike", "Hexproof",
                "Shroud", "Indestructible");
        return essential.contains(keyword);
    }

    private static boolean isEssentialAbility(SpellAbility ability) {
        // Skip abilities that are just "cast this spell" or basic creature abilities
        String desc = ability.getDescription();
        if (desc == null || desc.isEmpty()) return false;
        if (desc.contains("Creature") && desc.contains("/")) return false; // Skip creature stat lines
        return true;
    }

    private static List<String> extractEssentialStatusEffects(Player player) {
        List<String> effects = new ArrayList<>();
        if (player.hasKeyword("Hexproof")) effects.add("hexproof");
        if (player.hasKeyword("Shroud")) effects.add("shroud");
        return effects;
    }

    private static List<String> mapManaPool(ManaPool pool) {
        List<String> mana = new ArrayList<>();

        for (int i = 0; i < pool.getAmountOfColor((byte) 1); i++) mana.add("W");
        for (int i = 0; i < pool.getAmountOfColor((byte) 2); i++) mana.add("U");
        for (int i = 0; i < pool.getAmountOfColor((byte) 4); i++) mana.add("B");
        for (int i = 0; i < pool.getAmountOfColor((byte) 8); i++) mana.add("R");
        for (int i = 0; i < pool.getAmountOfColor((byte) 16); i++) mana.add("G");
        for (int i = 0; i < pool.getAmountOfColor((byte) 32); i++) mana.add("C");

        return mana;
    }

    private static Map<String, Integer> calculateAvailableMana(Player player) {
        Map<String, Integer> available = new HashMap<>();

        for (Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            if (!permanent.isTapped() && (permanent.isLand() || permanent.hasSVar("AddedMana"))) {
                if (permanent.isBasicLand()) {
                    String manaType = getBasicLandManaType(permanent.getName());
                    available.merge(manaType, 1, Integer::sum);
                }
                // TODO: Handle non-basic lands and mana artifacts
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