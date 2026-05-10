package forge.ai.grpc;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.proto.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProtoMapper {

    public static GameState mapGameState(Game game, Player perspective, String prompt, List<Action> availableActions) {
        GameState.Builder builder = GameState.newBuilder()
                .setTurnNumber(game.getPhaseHandler().getTurn())
                .setCurrentPhase(game.getPhaseHandler().getPhase().toString())
                .setCurrentStep(game.getPhaseHandler().getPhase().toString()) // PhaseHandler doesn't expose step easily
                .setActivePlayerId(game.getPhaseHandler().getPlayerTurn().getName())
                .setPerspectivePlayerId(perspective.getName());

        if (game.getPhaseHandler().getPriorityPlayer() != null) {
            builder.setPriorityPlayerId(game.getPhaseHandler().getPriorityPlayer().getName());
        }

        for (Player p : game.getPlayers()) {
            builder.addPlayers(mapPlayer(p, perspective.equals(p)));
        }

        for (SpellAbilityStackInstance si : game.getStack()) {
            builder.addStack(mapStackObject(si));
        }

        if (game.getCombat() != null) {
            builder.setCombat(mapCombat(game.getCombat(), game));
        }

        builder.setMonarchPlayerId(game.getMonarch() != null ? game.getMonarch().getName() : "");
        builder.setInitiativePlayerId(game.getHasInitiative() != null ? game.getHasInitiative().getName() : "");
        builder.setIsDay(game.getDayTime() != null && game.getDayTime());
        builder.setIsNight(game.getDayTime() != null && !game.getDayTime());

        builder.setDecisionContext(DecisionContext.newBuilder()
                .setPrompt(prompt != null ? prompt : "")
                .addAllAvailableActions(availableActions)
                .build());

        return builder.build();
    }

    private static PlayerState mapPlayer(Player player, boolean isPerspective) {
        PlayerState.Builder builder = PlayerState.newBuilder()
                .setPlayerId(player.getName())
                .setName(player.getName())
                .setLife(player.getLife())
                .setIsAlive(!player.hasLost())
                .setPoisonCounters(player.getPoisonCounters())
                .setHandSize(player.getZone(ZoneType.Hand).size())
                .setLibrarySize(player.getZone(ZoneType.Library).size())
                .setLandsPlayedThisTurn(player.getLandsPlayedThisTurn());

        if (isPerspective) {
            for (Card c : player.getCardsIn(ZoneType.Hand)) {
                builder.addHand(mapCard(c));
            }
        }

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            builder.addBattlefield(mapCard(c));
        }
        for (Card c : player.getCardsIn(ZoneType.Graveyard)) {
            builder.addGraveyard(mapCard(c));
        }
        for (Card c : player.getCardsIn(ZoneType.Exile)) {
            builder.addExile(mapCard(c));
        }
        for (Card c : player.getCardsIn(ZoneType.Command)) {
            builder.addCommand(mapCard(c));
        }

        builder.addAllManaPool(mapManaPool(player.getManaPool()));

        return builder.build();
    }

    private static CardState mapCard(Card card) {
        CardState.Builder builder = CardState.newBuilder()
                .setId(String.valueOf(card.getId()))
                .setName(card.getName())
                .setManaCost(card.getManaCost() != null ? card.getManaCost().toString() : "");
        
        // Map colors
        for (byte b : card.getColor()) {
            builder.addColors(forge.card.MagicColor.Color.fromByte(b).toString());
        }
        
        // Map types
        for (String type : card.getType()) {
            builder.addTypes(type);
        }

        builder.setPower(card.getNetPower())
                .setToughness(card.getNetToughness())
                .setTapped(card.isTapped())
                .setSummoningSick(card.hasSickness())
                .setFaceDown(card.isFaceDown())
                .setOwnerId(card.getOwner().getName())
                .setControllerId(card.getController().getName());

        for (Map.Entry<CounterType, Integer> entry : card.getCounters().entrySet()) {
            builder.putCounters(entry.getKey().toString(), entry.getValue());
        }

        for (Card attached : card.getAttachedCards()) {
            builder.addAttachments(String.valueOf(attached.getId()));
        }

        for (SpellAbility sa : card.getSpellAbilities()) {
            builder.addAbilities(AbilityState.newBuilder()
                    .setId(String.valueOf(sa.hashCode()))
                    .setDescription(sa.getDescription())
                    .setManaCost(sa.getPayCosts() != null ? sa.getPayCosts().toString() : "")
                    .setCanActivate(sa.canPlay())
                    .build());
        }

        return builder.build();
    }

    private static StackObject mapStackObject(SpellAbilityStackInstance si) {
        SpellAbility sa = si.getSpellAbility();
        StackObject.Builder builder = StackObject.newBuilder()
                .setId(String.valueOf(si.hashCode()))
                .setName(sa.getHostCard().getName())
                .setSourceCardId(String.valueOf(sa.getHostCard().getId()))
                .setControllerId(si.getActivatingPlayer().getName())
                .setDescription(sa.getDescription());

        if (sa.usesTargeting()) {
            for (GameObject target : sa.getTargets()) {
                if (target instanceof Card) {
                    builder.addTargetIds(String.valueOf(((Card) target).getId()));
                } else if (target instanceof Player) {
                    builder.addTargetIds(((Player) target).getName());
                }
            }
        }

        return builder.build();
    }

    private static CombatState mapCombat(Combat combat, Game game) {
        CombatState.Builder builder = CombatState.newBuilder()
                .setPhase(mapCombatPhase(game.getPhaseHandler().getPhase()));

        for (Card attacker : combat.getAttackers()) {
            Attacker.Builder ab = Attacker.newBuilder()
                    .setAttackerId(String.valueOf(attacker.getId()))
                    .setTargetId(combat.getDefendingPlayerRelatedTo(attacker).getName());
            
            for (Card blocker : combat.getBlockers(attacker)) {
                ab.addBlockerIds(String.valueOf(blocker.getId()));
            }
            builder.addAttackerAssignments(ab.build());
        }

        return builder.build();
    }

    private static CombatState.CombatPhase mapCombatPhase(PhaseType phase) {
        switch (phase) {
            case COMBAT_BEGIN: return CombatState.CombatPhase.BEGIN_COMBAT;
            case COMBAT_DECLARE_ATTACKERS: return CombatState.CombatPhase.DECLARE_ATTACKERS;
            case COMBAT_DECLARE_BLOCKERS: return CombatState.CombatPhase.DECLARE_BLOCKERS;
            case COMBAT_FIRST_STRIKE_DAMAGE: return CombatState.CombatPhase.FIRST_STRIKE_DAMAGE;
            case COMBAT_DAMAGE: return CombatState.CombatPhase.NORMAL_DAMAGE;
            case COMBAT_END: return CombatState.CombatPhase.END_COMBAT;
            default: return CombatState.CombatPhase.NOT_IN_COMBAT;
        }
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
}
