/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai;

import com.esotericsoftware.minlog.Log;
import com.google.common.collect.Lists;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.LearnAi;
import forge.ai.simulation.GameStateEvaluator;
import forge.ai.simulation.SpellAbilityPicker;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.replacement.ReplaceMoved;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static forge.ai.ComputerUtilMana.getAvailableManaEstimate;
import static java.lang.Math.max;

/**
 * Abstract base class for AI controllers in Forge.
 * Contains all the common AI logic and implementations.
 *
 * @author Forge Team
 */
public abstract class AiControllerAbstract {

    // Instance fields
    protected final Player player;
    protected final Game game;
    protected final AiCardMemory memory;
    protected Combat predictedCombat;
    protected Combat predictedCombatNextTurn;
    protected boolean cheatShuffle = false;
    protected boolean useSimulation = false;
    protected SpellAbilityPicker simPicker;
    protected int lastAttackAggression = 0;
    protected boolean useLivingEnd = false;
    protected List<SpellAbility> skipped = List.of();

    protected AiControllerAbstract(final Player computerPlayer, final Game game0) {
        player = computerPlayer;
        game = game0;
        memory = new AiCardMemory();
        simPicker = new SpellAbilityPicker(game, player);
    }

    // Basic getters and setters
    public final Player getPlayer() { return player; }
    public final Game getGame() { return game; }
    public final AiCardMemory getCardMemory() { return memory; }
    public final boolean canCheatShuffle() { return cheatShuffle; }
    public final void allowCheatShuffle(boolean canCheatShuffle) { this.cheatShuffle = canCheatShuffle; }
    public final boolean usesSimulation() { return useSimulation; }
    public final void setUseSimulation(boolean value) { this.useSimulation = value; }
    public final int getAttackAggression() { return lastAttackAggression; }
    public final SpellAbilityPicker getSimulationPicker() { return simPicker; }

    // Combat prediction methods
    public Combat getPredictedCombat() {
        if (predictedCombat == null) {
            AiAttackController aiAtk = new AiAttackController(player);
            predictedCombat = new Combat(player);
            aiAtk.declareAttackers(predictedCombat);
        }
        return predictedCombat;
    }

    public Combat getPredictedCombatNextTurn() {
        if (predictedCombatNextTurn == null) {
            AiAttackController aiAtk = new AiAttackController(player, true);
            predictedCombatNextTurn = new Combat(player);
            aiAtk.declareAttackers(predictedCombatNextTurn);
        }
        return predictedCombatNextTurn;
    }

    // Static utility methods
    public static List<SpellAbility> getPlayableCounters(CardCollection l) {
        final List<SpellAbility> spellAbility = Lists.newArrayList();
        for (final Card c : l) {
            if (c.isForetold() && c.getAlternateState() != null) {
                try {
                    for (final SpellAbility sa : c.getAlternateState().getNonManaAbilities()) {
                        if (sa.getApi() == ApiType.Counter) {
                            spellAbility.add(sa);
                        } else {
                            if (sa.getApi() != null && sa.getApi().toString().contains("Foretell") && c.getAlternateState().getName().equalsIgnoreCase("Saw It Coming"))
                                spellAbility.add(sa);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                for (final SpellAbility sa : c.getNonManaAbilities()) {
                    if (sa.getApi() == ApiType.Counter) {
                        spellAbility.add(sa);
                    }
                }
            }
        }
        return spellAbility;
    }

    public static <T> List<T> filterList(List<T> input, Predicate<? super T> pred) {
        List<T> filtered = input.stream().filter(pred).collect(Collectors.toList());
        input.removeAll(filtered);
        return filtered;
    }

    public static <T extends TriggerReplacementBase> List<T> filterList(List<T> input, Function<SpellAbility, Object> pred, Object value) {
        return filterList(input, trb -> pred.apply(trb.ensureAbility()) == value);
    }

    public static List<SpellAbility> filterListByApi(List<SpellAbility> input, ApiType type) {
        return filterList(input, SpellAbilityPredicates.isApi(type));
    }

    // Concrete implementations of all AI logic

    public boolean checkETBEffects(final Card card, final SpellAbility sa, final ApiType api) {
        boolean reset = false;
        if (card.getCastSA() == null) {
            card.setCastSA(sa);
            reset = true;
        }
        boolean result = checkETBEffectsPreparedCard(card, sa, api);
        if (reset) {
            card.setCastSA(null);
        }
        return result;
    }

    public SpellAbility predictSpellToCastInMain2(ApiType exceptSA) {
        return predictSpellToCastInMain2(exceptSA, true);
    }

    public boolean reserveManaSourcesForNextSpell(SpellAbility sa, SpellAbility exceptForSa) {
        return reserveManaSources(sa, null, false, true, exceptForSa);
    }

    public boolean reserveManaSources(SpellAbility sa) {
        return reserveManaSources(sa, PhaseType.MAIN2, false, false, null);
    }

    public boolean reserveManaSources(SpellAbility sa, PhaseType phaseType, boolean enemy) {
        return reserveManaSources(sa, phaseType, enemy, true, null);
    }

    public boolean reserveManaSources(SpellAbility sa, PhaseType phaseType, boolean enemy, boolean forNextSpell, SpellAbility exceptForThisSa) {
        ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, true, 0, false);
        CardCollection manaSources = ComputerUtilMana.getManaSourcesToPayCost(cost, sa, player);

        if (exceptForThisSa != null) {
            manaSources.removeAll(ComputerUtilMana.getManaSourcesToPayCost(
                    ComputerUtilMana.calculateManaCost(exceptForThisSa.getPayCosts(), exceptForThisSa, true, 0, false),
                    exceptForThisSa, player));
        }

        if (manaSources.isEmpty()) {
            return false;
        }

        AiCardMemory.MemorySet memSet = null;
        if (phaseType == null && forNextSpell) {
            memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        } else if (phaseType != null) {
            switch (phaseType) {
                case MAIN2:
                    memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                    break;
                case COMBAT_DECLARE_BLOCKERS:
                    memSet = enemy ? AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK
                            : AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK;
                    break;
                default:
                    System.out.println("Warning: unsupported mana reservation phase specified for reserveManaSources: "
                            + phaseType.name() + ", reserving until Main 2 instead. Consider adding support for the phase if needed.");
                    memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                    break;
            }
        }

        if (manaSources.size() >= cost.getConvertedManaCost()) {
            for (Card c : manaSources) {
                memory.rememberCard(c, memSet);
            }
            return true;
        }

        return false;
    }

    public List<SpellAbility> getPossibleETBCounters() {
        CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollectionView ccvPlayerLibrary = player.getCardsIn(ZoneType.Library);

        all.addAll(player.getCardsIn(ZoneType.Exile));
        all.addAll(player.getCardsIn(ZoneType.Graveyard));
        if (!ccvPlayerLibrary.isEmpty()) {
            all.add(ccvPlayerLibrary.get(0));
        }

        all.addAll(player.getOpponents().getCardsIn(ZoneType.Exile));

        final List<SpellAbility> spellAbilities = Lists.newArrayList();
        for (final Card c : all) {
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (sa instanceof SpellPermanent) {
                    sa.setActivatingPlayer(player);
                    if (checkETBEffects(c, sa, ApiType.Counter)) {
                        spellAbilities.add(sa);
                    }
                }
            }
        }
        return spellAbilities;
    }

    public boolean checkCurseEffects(SpellAbility sa) {
        CardCollectionView ccvGameBattlefield = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.hasSVar("AICurseEffect"));
        for (final Card c : ccvGameBattlefield) {
            final String curse = c.getSVar("AICurseEffect");
            if ("NonActive".equals(curse) && !player.equals(game.getPhaseHandler().getPlayerTurn())) {
                return true;
            } else {
                final Card host = sa.getHostCard();
                if ("DestroyCreature".equals(curse) && sa.isSpell() && host.isCreature()
                        && !host.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                    return true;
                } else if ("CounterEnchantment".equals(curse) && sa.isSpell() && host.isEnchantment() && sa.isCounterableBy(null)) {
                    return true;
                } else if ("ChaliceOfTheVoid".equals(curse) && sa.isSpell() && sa.isCounterableBy(null)
                        && host.getCMC() == c.getCounters(CounterEnumType.CHARGE)) {
                    return true;
                } else if ("BazaarOfWonders".equals(curse) && sa.isSpell() && sa.isCounterableBy(null)) {
                    String hostName = host.getName();
                    for (Card card : ccvGameBattlefield) {
                        if (!card.isToken() && card.sharesNameWith(host)) {
                            return true;
                        }
                    }
                    if (game.getCardsIn(ZoneType.Graveyard).anyMatch(CardPredicates.nameEquals(hostName))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean checkETBEffectsPreparedCard(Card card, SpellAbility sa, ApiType api) {
        final Player activator = sa.getActivatingPlayer();

        // Replacement effects
        for (final ReplacementEffect re : card.getReplacementEffects()) {
            if (!(re instanceof ReplaceMoved)) {
                continue;
            }

            if (!ZoneType.Battlefield.toString().equals(re.getParam("Destination"))) {
                continue;
            }

            if (re.hasParam("ValidCard")) {
                String validCard = re.getParam("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("!kicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) {
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) {
                        continue;
                    }
                }
            }

            if (!re.requirementsCheck(game)) {
                continue;
            }
            SpellAbility exSA = re.getOverridingAbility();

            if (exSA != null) {
                exSA = exSA.copy(activator);

                if ((exSA instanceof AbilitySub) && !doTrigger(exSA, false)) {
                    return false;
                }
            }
        }

        boolean rightapi = false;

        // Trigger play improvements
        for (final Trigger tr : card.getTriggers()) {
            if (tr.getMode() != TriggerType.ChangesZone) {
                continue;
            }

            if (tr.isKeyword(Keyword.PARTNER)) {
                continue;
            }

            if (!ZoneType.Battlefield.toString().equals(tr.getParam("Destination"))) {
                continue;
            }

            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(tr.getHostCard());
            runParams.put(AbilityKey.Destination, ZoneType.Battlefield.name());
            if (StaticAbilityDisableTriggers.disabled(game, tr, runParams)) {
                return api == null;
            }

            if (tr.hasParam("ValidCard")) {
                String validCard = tr.getParam("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("!kicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) {
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) {
                        continue;
                    }
                }
            }

            if (!tr.requirementsCheck(game)) {
                continue;
            }

            if (tr.hasParam("OptionalDecider") && api == null) {
                continue;
            }

            SpellAbility exSA = tr.ensureAbility().copy(activator);

            if (api != null) {
                if (exSA.getApi() != api) {
                    continue;
                }
                rightapi = true;
                if (!(exSA instanceof AbilitySub) && !ComputerUtilCost.canPayCost(exSA, player, true)) {
                    return false;
                }
            }

            exSA.setTrigger(tr);
            exSA.setTriggeringObject(AbilityKey.Card, card);

            SpellAbilityCondition cons = exSA.getConditions();
            if (cons != null) {
                String pres = cons.getIsPresent();
                if (pres != null && pres.matches("Card\\.(Strictly)?Self")) {
                    cons.setIsPresent(null);
                }
            }

            if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                if (api == null && card.isCreature() && !ComputerUtilAbility.isFullyTargetable(exSA) &&
                        (ComputerUtil.aiLifeInDanger(activator, true, 0) || "BadETB".equals(tr.getParam("AILogic")))) {
                    continue;
                }
                return false;
            }
        }

        if (card.isSaga()) {
            for (final Trigger tr : card.getTriggers()) {
                if (tr.getMode() != TriggerType.CounterAdded || !tr.isChapter()) {
                    continue;
                }

                SpellAbility exSA = tr.ensureAbility().copy(activator);

                if (api != null && exSA.getApi() == api) {
                    rightapi = true;
                }

                if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                    return false;
                }

                break;
            }
        }

        if (api != null && !rightapi) {
            return false;
        }

        return true;
    }

    public CardCollection filterLandsToPlay(CardCollection landList) {
        final CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        CardCollection nonLandList = CardLists.filter(hand, CardPredicates.NON_LANDS);
        if (landList.size() == 1 && nonLandList.size() < 3) {
            CardCollectionView cardsInPlay = player.getCardsIn(ZoneType.Battlefield);
            CardCollection landsInPlay = CardLists.filter(cardsInPlay, CardPredicates.LANDS);
            CardCollection allCards = new CardCollection(player.getCardsIn(ZoneType.Graveyard));
            allCards.addAll(player.getCardsIn(ZoneType.Command));
            allCards.addAll(cardsInPlay);
            int maxCmcInHand = Aggregates.max(hand, Card::getCMC);
            int max = max(maxCmcInHand, 6);
            if (landsInPlay.size() + landList.size() > max) {
                for (Card c : allCards) {
                    for (SpellAbility sa : c.getSpellAbilities()) {
                        Cost payCosts = sa.getPayCosts();
                        if (payCosts != null) {
                            for (CostPart part : payCosts.getCostParts()) {
                                if (part instanceof CostDiscard) {
                                    return null;
                                }
                            }
                        }
                    }
                }
            }
        }

        landList = CardLists.filter(landList, c -> {
            if (canPlaySpellBasic(c, null) != AiPlayDecision.WillPlay) {
                return false;
            }
            String name = c.getName();
            CardCollectionView battlefield = player.getCardsIn(ZoneType.Battlefield);
            if (c.getType().isLegendary() && !name.equals("Flagstones of Trokair")) {
                if (battlefield.anyMatch(CardPredicates.nameEquals(name))) {
                    return false;
                }
            }

            final CardCollectionView hand1 = player.getCardsIn(ZoneType.Hand);
            CardCollection lands = new CardCollection(battlefield);
            lands.addAll(hand1);
            lands = CardLists.filter(lands, CardPredicates.LANDS);
            int maxCmcInHand = Aggregates.max(hand1, Card::getCMC);

            if (lands.size() >= max(maxCmcInHand, 6)) {
                if (!c.isLand() || (c.isModal() && !c.getState(CardStateName.Modal).getType().isLand())) {
                    return false;
                }

                if (c.hasKeyword(Keyword.CYCLING)) {
                    return false;
                }
            }
            return c.getAllPossibleAbilities(player, true).stream().anyMatch(SpellAbility::isLandAbility);
        });
        return landList;
    }

    public Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) {
            return null;
        }

        landList = ComputerUtilCard.dedupeCards(landList);

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);

        boolean hasMomir = player.isCardInCommand("Momir Vig, Simic Visionary Avatar");
        if (hasMomir && nonLandsInHand.isEmpty()) {
            String landStrategy = getProperty(AiProps.MOMIR_BASIC_LAND_STRATEGY);
            if (landStrategy.equalsIgnoreCase("random")) {
                return Aggregates.random(landList);
            } else if (landStrategy.toLowerCase().startsWith("preforder:")) {
                String order = landStrategy.substring(10);
                for (char c : order.toCharArray()) {
                    byte color = MagicColor.fromName(c);
                    for (Card land : landList) {
                        for (final SpellAbility m : ComputerUtilMana.getAIPlayableMana(land)) {
                            if (m.canProduce(MagicColor.toShortString(color))) {
                                return land;
                            }
                        }
                    }
                }
                return Aggregates.random(landList);
            }
        }

        CardCollection unreflectedLands = new CardCollection(landList);
        for (Card l : landList) {
            if (l.isReflectedLand()) {
                unreflectedLands.remove(l);
            }
        }
        if (!unreflectedLands.isEmpty()) {
            landList = unreflectedLands;
        }

        if (!nonLandsInHand.isEmpty()) {
            CardCollection nonTappedLands = new CardCollection();
            for (Card land : landList) {
                final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(land);
                repParams.put(AbilityKey.Origin, land.getZone().getZoneType());
                repParams.put(AbilityKey.Destination, ZoneType.Battlefield);

                GameEntityCounterTable table = new GameEntityCounterTable();
                repParams.put(AbilityKey.EffectOnly, true);
                repParams.put(AbilityKey.CounterTable, table);
                repParams.put(AbilityKey.CounterMap, table.column(land));

                boolean foundTapped = false;
                for (ReplacementEffect re : player.getGame().getReplacementHandler().getReplacementList(ReplacementType.Moved, repParams, ReplacementLayer.Other)) {
                    SpellAbility reSA = re.ensureAbility();
                    if (reSA == null || !ApiType.Tap.equals(reSA.getApi())) {
                        continue;
                    }
                    reSA.setActivatingPlayer(reSA.getHostCard().getController());
                    if (reSA.metConditions()) {
                        foundTapped = true;
                        break;
                    }
                }

                if (foundTapped) {
                    continue;
                }

                nonTappedLands.add(land);
            }

            if (!nonTappedLands.isEmpty()) {
                int mana_available = getAvailableManaEstimate(player);
                if (mana_available > 6) {
                    landList = nonTappedLands;
                } else {
                    int max_inc = 0;
                    for (Card c : nonTappedLands) {
                        max_inc = max(max_inc, c.getMaxManaProduced());
                    }
                    if (max_inc > 0) {
                        boolean found = false;
                        for (Card c : nonLandsInHand) {
                            ManaCost cost = c.getManaCost();
                            if ((cost.getCMC() - mana_available) * (cost.getCMC() - mana_available - max_inc - 1) < 0 ||
                                    (cost.countX() > 0 && cost.getCMC() >= mana_available)) {
                                found = true;
                                break;
                            }
                        }

                        if (found) {
                            landList = nonTappedLands;
                        }
                    }
                }
            }
        }

        if (landList.size() == 1) {
            return landList.get(0);
        }

        if (player.getLandsInPlay().isEmpty()) {
            CardCollection oneDrops = CardLists.filter(nonLandsInHand, CardPredicates.hasCMC(1));
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                byte color = MagicColor.WUBRG[i];
                if (oneDrops.anyMatch(CardPredicates.isColor(color))) {
                    for (Card land : landList) {
                        if (land.getType().hasSubtype(MagicColor.Constant.BASIC_LANDS.get(i))) {
                            return land;
                        }
                        for (final SpellAbility m : ComputerUtilMana.getAIPlayableMana(land)) {
                            if (m.canProduce(MagicColor.toShortString(color))) {
                                return land;
                            }
                        }
                    }
                }
            }
        }

        final CardCollectionView landsInBattlefield = player.getCardsIn(ZoneType.Battlefield);
        final List<String> basics = Lists.newArrayList();

        int[] counts = new int[6];

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility m : c.getManaAbilities()) {
                m.setActivatingPlayer(c.getController());
                for (AbilityManaPart mp : m.getAllManaParts()) {
                    for (String part : mp.mana(m).split(" ")) {
                        int index = ManaAtom.getIndexFromName(part);
                        if (index != -1) {
                            counts[index] += 1;
                        }
                    }
                }
            }
        }

        int[] basic_counts = new int[5];
        for (final String name : MagicColor.Constant.BASIC_LANDS) {
            if (!CardLists.getType(landList, name).isEmpty()) {
                basics.add(name);
            }
        }
        if (!basics.isEmpty()) {
            for (int i = 0; i < MagicColor.Constant.BASIC_LANDS.size(); i++) {
                String b = MagicColor.Constant.BASIC_LANDS.get(i);
                final int num = CardLists.getType(landsInBattlefield, b).size();
                basic_counts[i] = num;
            }
        }

        Card toReturn = Aggregates.itemWithMax(IterableUtil.filter(landList, Card::hasPlayableLandFace),
                (card -> {
                    int score = GameStateEvaluator.evaluateLand(card);
                    for (String cardType : card.getType()) {
                        int index = MagicColor.Constant.BASIC_LANDS.indexOf(cardType);
                        if (index != -1 && basic_counts[index] == 0) {
                            score += 25;
                        }
                    }

                    int[] card_counts = new int[6];
                    for (SpellAbility m : card.getManaAbilities()) {
                        m.setActivatingPlayer(card.getController());
                        for (AbilityManaPart mp : m.getAllManaParts()) {
                            for (String part : mp.mana(m).split(" ")) {
                                int index = ManaAtom.getIndexFromName(part);
                                if (index != -1) {
                                    card_counts[index] += 1;
                                }
                            }
                        }
                    }

                    for (int i = 0; i < card_counts.length; i++) {
                        int diff = (card_counts[i] * 50) / (counts[i] + 1);
                        score += diff;
                    }

                    return score;
                }));
        return toReturn;
    }

    public SpellAbility chooseCounterSpell(List<SpellAbility> possibleCounters) {
        if (possibleCounters == null || possibleCounters.isEmpty()) {
            return null;
        }
        SpellAbility bestSA = null;
        int bestRestriction = Integer.MIN_VALUE;

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(possibleCounters, player)) {
            SpellAbility currentSA = sa;
            sa.setActivatingPlayer(player);

            AiPlayDecision opinion = canPlayAndPayFor(currentSA);
            if (opinion == AiPlayDecision.WillPlay) {
                if (bestSA == null) {
                    bestSA = currentSA;
                    bestRestriction = ComputerUtil.counterSpellRestriction(player, currentSA);
                } else {
                    final int restrictionLevel = ComputerUtil.counterSpellRestriction(player, currentSA);

                    if (restrictionLevel > bestRestriction) {
                        bestRestriction = restrictionLevel;
                        bestSA = currentSA;
                    }
                }
            }
        }

        return bestSA;
    }

    public SpellAbility predictSpellToCastInMain2(ApiType exceptSA, boolean handOnly) {
        if (!getBooleanProperty(AiProps.PREDICT_SPELLS_FOR_MAIN2)) {
            return null;
        }

        final CardCollectionView cards = handOnly ? player.getCardsIn(ZoneType.Hand) :
                ComputerUtilAbility.getAvailableCards(game, player);

        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);

        try {
            all.sort(ComputerUtilAbility.saEvaluator);
            ComputerUtilAbility.sortCreatureSpells(all);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            String assertex = ComparatorUtil.verifyTransitivity(ComputerUtilAbility.saEvaluator, all);
            Sentry.captureMessage(ex.getMessage() + "\nAssertionError [verifyTransitivity]: " + assertex);
        }

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            ApiType saApi = sa.getApi();

            if (saApi == ApiType.Counter || saApi == exceptSA) {
                continue;
            }
            sa.setActivatingPlayer(player);
            Card host = sa.getHostCard();
            if (sa instanceof SpellPermanent && host != null && !host.isLand() && !ComputerUtil.castPermanentInMain1(player, sa) && ComputerUtilCost.canPayCost(sa, player, false)) {
                return sa;
            }
        }
        return null;
    }

    public AiPlayDecision canPlayAndPayFor(SpellAbility sa) {
        if (!sa.canPlay()) {
            return AiPlayDecision.CantPlaySa;
        }

        final Card host = sa.getHostCard();

        CardStateName currentState = sa.getCardState() != null && host.getCurrentStateName() != sa.getCardStateName() && !host.isInPlay() ? host.getCurrentStateName() : null;
        if (currentState != null) {
            host.setState(sa.getCardStateName(), false);
        }
        if (sa.isSpell()) {
            host.setCastSA(sa);
        }

        AiPlayDecision decision = canPlayAndPayForFace(sa);

        if (sa.isSpell()) {
            host.setCastSA(null);
        }
        if (currentState != null) {
            host.setState(currentState, false);
        }

        return decision;
    }

    public AiPlayDecision canPlayAndPayForFace(SpellAbility sa) {
        final Card host = sa.getHostCard();

        if (sa.hasParam("AICheckSVar")) {
            final String svarToCheck = sa.getParam("AICheckSVar");
            String comparator = "GE";
            int compareTo = 1;

            if (sa.hasParam("AISVarCompare")) {
                final String fullCmp = sa.getParam("AISVarCompare");
                comparator = fullCmp.substring(0, 2);
                final String strCmpTo = fullCmp.substring(2);
                try {
                    compareTo = Integer.parseInt(strCmpTo);
                } catch (final Exception ignored) {
                    compareTo = AbilityUtils.calculateAmount(host, host.getSVar(strCmpTo), sa);
                }
            }

            int left = AbilityUtils.calculateAmount(host, svarToCheck, sa);
            if (!Expressions.compare(left, comparator, compareTo)) {
                return AiPlayDecision.AnotherTime;
            }
        }

        int oldCMC = -1;
        boolean xCost = sa.costHasX() || host.hasKeyword(Keyword.STRIVE) || sa.getApi() == ApiType.Charm;
        if (!xCost) {
            if (!ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
                return AiPlayDecision.CantAfford;
            }
            if (!sa.getAllTargetChoices().isEmpty()) {
                oldCMC = CostAdjustment.adjust(sa.getPayCosts(), sa, false).getTotalMana().getCMC();
            }
        }

        AiPlayDecision canPlay = canPlaySa(sa);

        if (canPlay != AiPlayDecision.WillPlay) {
            return canPlay;
        }

        if (!sa.isSpell() || sa.isCounterableBy(null)) {
            for (TargetChoices tc : sa.getAllTargetChoices()) {
                for (Card tgt : tc.getTargetCards()) {
                    if (tgt.hasKeyword(Keyword.WARD) && tgt.isInPlay() && tgt.getController().isOpponentOf(host.getController())) {
                        Cost wardCost = ComputerUtilCard.getTotalWardCost(tgt);
                        if (wardCost.hasManaCost()) {
                            xCost |= wardCost.getTotalMana().getCMC() > 0;
                        }
                        SpellAbilityAi topAI = new SpellAbilityAi() {
                        };
                        if (!topAI.willPayCosts(player, sa, wardCost, host)) {
                            return AiPlayDecision.CostNotAcceptable;
                        }
                    }
                }
            }
        }

        if (!xCost && oldCMC > -1) {
            int finalCMC = CostAdjustment.adjust(sa.getPayCosts(), sa, false).getTotalMana().getCMC();
            if (finalCMC > oldCMC) {
                xCost = true;
            }
        }

        if (xCost && !ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
            return AiPlayDecision.CantAfford;
        }

        Set<Card> tappedForMana = AiCardMemory.getMemorySet(player, AiCardMemory.MemorySet.PAYS_TAP_COST);
        if (tappedForMana != null && tappedForMana.isEmpty() &&
                !ComputerUtilCost.checkTapTypeCost(player, sa.getPayCosts(), host, sa, new CardCollection(tappedForMana))) {
            return AiPlayDecision.CantAfford;
        }

        return AiPlayDecision.WillPlay;
    }

    public AiPlayDecision canPlaySpellBasic(Card card, SpellAbility sa) {
        if ("True".equals(card.getSVar("NonStackingEffect")) && ComputerUtilCard.isNonDisabledCardInPlay(player, card.getName())) {
            return AiPlayDecision.NeedsToPlayCriteriaNotMet;
        }

        return ComputerUtilCard.checkNeedsToPlayReqs(card, sa);
    }

    public boolean canPlaySpellWithoutBuyback(Card card, SpellAbility sa) {
        int copies = CardLists.count(player.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals(card.getName()));
        if (copies >= 2) {
            return true;
        }

        if (ComputerUtil.aiLifeInDanger(player, true, 0)) {
            return true;
        }

        Cost costWithBuyback = sa.getPayCosts().copy();
        for (OptionalCostValue opt : GameActionUtil.getOptionalCostValues(sa)) {
            if (opt.getType() == OptionalCost.Buyback) {
                costWithBuyback.add(opt.getCost());
            }
        }
        costWithBuyback = CostAdjustment.adjust(costWithBuyback, sa, false);
        if (costWithBuyback.hasSpecificCostType(CostPayLife.class)
                || costWithBuyback.hasSpecificCostType(CostDiscard.class)
                || costWithBuyback.hasSpecificCostType(CostSacrifice.class)) {
            return true;
        }

        int neededMana = 0;
        if (costWithBuyback.getCostMana() != null) {
            neededMana = costWithBuyback.getCostMana().getMana().getCMC();
        }
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            for (StaticAbility s : c.getStaticAbilities()) {
                if ("ReduceCost".equals(s.getParam("Mode"))
                        && "Spell.Buyback".equals(s.getParam("ValidSpell"))) {
                    neededMana -= AbilityUtils.calculateAmount(c, s.getParam("Amount"), s);
                }
            }
        }
        if (neededMana < 0) {
            neededMana = 0;
        }

        int hasMana = getAvailableManaEstimate(player, false);
        if (hasMana < neededMana - 1) {
            return true;
        }

        return false;
    }

    public List<SpellAbility> singleSpellAbilityList(SpellAbility sa) {
        if (sa == null) {
            return null;
        }
        return Lists.newArrayList(sa);
    }

    public boolean isSafeToHoldLandDropForMain2(Card landToPlay) {
        boolean hasMomir = player.isCardInCommand("Momir Vig, Simic Visionary Avatar");
        if (hasMomir) {
            return false;
        }

        if (!MyRandom.percentTrue(getIntProperty(AiProps.HOLD_LAND_DROP_FOR_MAIN2_IF_UNUSED))) {
            return false;
        }
        if (game.getPhaseHandler().getTurn() <= 2) {
            return false;
        }

        CardCollection inHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);
        CardCollectionView otb = player.getCardsIn(ZoneType.Battlefield);

        if (getBooleanProperty(AiProps.HOLD_LAND_DROP_ONLY_IF_HAVE_OTHER_PERMS)) {
            if (!otb.anyMatch(CardPredicates.NON_LANDS)) {
                return false;
            }
        }

        boolean isTapLand = false;
        for (ReplacementEffect repl : landToPlay.getReplacementEffects()) {
            if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters tapped.")) {
                isTapLand = true;
            }
        }

        int totalCMCInHand = Aggregates.sum(inHand, Card::getCMC);
        int minCMCInHand = Aggregates.min(inHand, Card::getCMC);
        if (minCMCInHand == Integer.MAX_VALUE)
            minCMCInHand = 0;
        int predictedMana = getAvailableManaEstimate(player, true);

        boolean canCastWithLandDrop = (predictedMana + 1 >= minCMCInHand) && minCMCInHand > 0 && !isTapLand;
        boolean cantCastAnythingNow = predictedMana < minCMCInHand;

        boolean hasRelevantAbsOTB = otb.anyMatch(card -> {
            boolean isTapLand1 = false;
            for (ReplacementEffect repl : card.getReplacementEffects()) {
                if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters tapped.")) {
                    isTapLand1 = true;
                }
            }

            for (SpellAbility sa : card.getSpellAbilities()) {
                if (sa.isAbility()
                        && sa.getPayCosts().getCostMana() != null
                        && sa.getPayCosts().getCostMana().getMana().getCMC() > 0
                        && (!sa.getPayCosts().hasTapCost() || !isTapLand1)
                        && (!sa.hasParam("ActivationZone") || sa.getParam("ActivationZone").contains("Battlefield"))) {
                    return true;
                }
            }
            return false;
        });

        boolean hasLandBasedEffect = otb.anyMatch(card -> {
            for (Trigger t : card.getTriggers()) {
                Map<String, String> params = t.getMapParams();
                if ("ChangesZone".equals(params.get("Mode"))
                        && params.containsKey("ValidCard")
                        && (!params.containsKey("AILogic") || !params.get("AILogic").equals("SafeToHold"))
                        && !params.get("ValidCard").contains("nonLand")
                        && ((params.get("ValidCard").contains("Land")) || (params.get("ValidCard").contains("Permanent")))
                        && "Battlefield".equals(params.get("Destination"))) {
                    return true;
                }
            }
            for (String sv : card.getSVars().keySet()) {
                String varValue = card.getSVar(sv);
                if (varValue.equals("Count$Domain")) {
                    for (String type : landToPlay.getType().getLandTypes()) {
                        if (CardType.isABasicLandType(type) && CardLists.getType(otb, type).isEmpty()) {
                            return true;
                        }
                    }
                }
                if (varValue.startsWith("Count$Valid") || sv.equals("BuffedBy")) {
                    if (varValue.contains("Land") || varValue.contains("Plains") || varValue.contains("Forest")
                            || varValue.contains("Mountain") || varValue.contains("Island") || varValue.contains("Swamp")
                            || varValue.contains("Wastes")) {
                        return true;
                    }
                }
            }
            return false;
        });

        if (!canCastWithLandDrop && cantCastAnythingNow && !hasLandBasedEffect && (!hasRelevantAbsOTB || isTapLand)) {
            return true;
        }
        if ((predictedMana <= totalCMCInHand && canCastWithLandDrop) || (hasRelevantAbsOTB && !isTapLand) || hasLandBasedEffect) {
            return false;
        }

        return true;
    }

    public SpellAbility getSpellAbilityToPlay() {
        if (skipped != null) {
            for (SpellAbility sa : skipped) {
                sa.setSkip(false);
            }
        }
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        List<SpellAbility> saList = Lists.newArrayList();

        SpellAbility top = null;
        if (!game.getStack().isEmpty()) {
            top = game.getStack().peekAbility();
        }
        final boolean topOwnedByAI = top != null && top.getActivatingPlayer().equals(player);

        boolean mustRespond = false;
        if (top != null) {
            mustRespond = top.hasParam("AIRespondsToOwnAbility");
            mustRespond |= top.isTrigger() && top.getTrigger().isKeyword(Keyword.EVOKE);
        }

        if (topOwnedByAI) {
            if (!mustRespond) {
                saList = ComputerUtilAbility.getSpellAbilities(cards, player);
                if (ComputerUtilAbility.getFirstCopySASpell(saList) == null) {
                    return null;
                }
            }
        }

        if (!game.getStack().isEmpty()) {
            SpellAbility counter = chooseCounterSpell(getPlayableCounters(cards));
            if (counter != null) return counter;

            SpellAbility counterETB = chooseSpellAbilityToPlayFromList(getPossibleETBCounters(), false);
            if (counterETB != null)
                return counterETB;
        }

        if (saList.isEmpty()) {
            saList = ComputerUtilAbility.getSpellAbilities(cards, player);
        }

        saList.removeIf(spellAbility -> {
            return spellAbility.isLandAbility() || (spellAbility.getHostCard() != null && ComputerUtilCard.isCardRemAIDeck(spellAbility.getHostCard()));
        });
        skipped = saList.stream().filter(SpellAbility::isSkip).collect(Collectors.toList());
        if (!skipped.isEmpty())
            saList.removeAll(skipped);
        useLivingEnd = IterableUtil.any(player.getZone(ZoneType.Library), CardPredicates.nameEquals("Living End"));

        SpellAbility chosenSa = chooseSpellAbilityToPlayFromList(saList, true);

        if (topOwnedByAI && !mustRespond && chosenSa != ComputerUtilAbility.getFirstCopySASpell(saList)) {
            return null;
        }

        return chosenSa;
    }

    public SpellAbility chooseSpellAbilityToPlayFromList(List<SpellAbility> all, boolean skipCounter) {
        if (all == null || all.isEmpty())
            return null;

        try {
            all.sort(ComputerUtilAbility.saEvaluator);
            ComputerUtilAbility.sortCreatureSpells(all);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            String assertex = ComparatorUtil.verifyTransitivity(ComputerUtilAbility.saEvaluator, all);
            Sentry.captureMessage(ex.getMessage() + "\nAssertionError [verifyTransitivity]: " + assertex);
        }

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SpellAbility> future = executor.submit(() -> {
            boolean isLifeInDanger = useLivingEnd && ComputerUtil.aiLifeInDanger(player, true, 0);
            for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
                if (skipCounter && sa.getApi() == ApiType.Counter) {
                    continue;
                }

                if (sa.getHostCard().hasKeyword(Keyword.STORM)
                        && sa.getApi() != ApiType.Counter
                        && player.getZone(ZoneType.Hand).contains(
                        Predicate.not(CardPredicates.LANDS.or(CardPredicates.hasKeyword("Storm")))
                )) {
                    if (game.getView().getStormCount() < this.getIntProperty(AiProps.MIN_COUNT_FOR_STORM_SPELLS)) {
                        continue;
                    }
                }

                AiPlayDecision aiPlayDecision = AiPlayDecision.CantPlaySa;
                if (useLivingEnd) {
                    if (sa.isCycling() && sa.canCastTiming(player)
                            && player.getCardsIn(ZoneType.Library).size() >= 10) {
                        if (ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
                            if (sa.getPayCosts() != null && sa.getPayCosts().hasSpecificCostType(CostPayLife.class)
                                    && !player.cantLoseForZeroOrLessLife() && player.getLife() <= sa.getPayCosts()
                                    .getCostPartByType(CostPayLife.class).getAbilityAmount(sa) * 2) {
                                aiPlayDecision = AiPlayDecision.CantAfford;
                            } else {
                                aiPlayDecision = AiPlayDecision.WillPlay;
                            }
                        }
                    } else if (sa.getHostCard().hasKeyword(Keyword.CASCADE)) {
                        if (isLifeInDanger) {
                            aiPlayDecision = player.getCreaturesInPlay().size() >= 4 ? AiPlayDecision.CantPlaySa
                                    : AiPlayDecision.WillPlay;
                        } else if (CardLists
                                .filter(player.getZone(ZoneType.Graveyard).getCards(), CardPredicates.CREATURES)
                                .size() > 4) {
                            if (player.getCreaturesInPlay().size() >= 4)
                                continue;
                            else if (!sa.getHostCard().isPermanent() && sa.canCastTiming(player)
                                    && ComputerUtilCost.canPayCost(sa, player, sa.isTrigger()))
                                aiPlayDecision = AiPlayDecision.WillPlay;
                        } else {
                            continue;
                        }
                    }
                }

                sa.setActivatingPlayer(player);
                SpellAbility root = sa.getRootAbility();

                if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                    sa.setLastStateBattlefield(game.getLastStateBattlefield());
                    sa.setLastStateGraveyard(game.getLastStateGraveyard());
                }

                AiPlayDecision opinion = useLivingEnd && AiPlayDecision.WillPlay.equals(aiPlayDecision) ? aiPlayDecision : canPlayAndPayFor(sa);

                sa.clearLastState();

                if (opinion != AiPlayDecision.WillPlay)
                    continue;

                return sa;
            }

            return null;
        });

        try {
            return future.get(game.getAITimeout(), TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    public boolean checkAiSpecificRestrictions(SpellAbility sa) {
        if (sa.hasParam("AILifeThreshold")) {
            return player.getLife() > Integer.parseInt(sa.getParam("AILifeThreshold"));
        }

        return true;
    }

    public <T extends CardTraitBase> List<T> filterListByAiLogic(List<T> list, String logic) {
        return filterList(list, CardTraitPredicates.hasParam("AILogic", logic));
    }

    // All concrete implementations from OldAiController

    public AiPlayDecision canPlaySa(SpellAbility sa) {
        if (!checkAiSpecificRestrictions(sa)) {
            return AiPlayDecision.CantPlayAi;
        }
        if (sa instanceof WrappedAbility) {
            return canPlaySa(((WrappedAbility) sa).getWrappedAbility());
        }

        if (!sa.canCastTiming(player)) {
            return AiPlayDecision.AnotherTime;
        }

        final Card card = sa.getHostCard();

        if (getBooleanProperty(AiProps.TRY_TO_PRESERVE_BUYBACK_SPELLS)) {
            if (card.hasKeyword(Keyword.BUYBACK) && !sa.isBuyback() && !canPlaySpellWithoutBuyback(card, sa)) {
                return AiPlayDecision.NeedsToPlayCriteriaNotMet;
            }
        }

        memory.clearMemorySet(AiCardMemory.MemorySet.MARKED_TO_AVOID_REENTRY);

        if (sa.getApi() != null) {

            String msg = "AiController:canPlaySa: AI checks for if can PlaySa";
            Breadcrumb bread = new Breadcrumb(msg);
            bread.setData("Api", sa.getApi().toString());
            bread.setData("Card", card.getName());
            bread.setData("SA", sa.toString());
            Sentry.addBreadcrumb(bread);

            Sentry.setExtra("Card", card.getName());
            Sentry.setExtra("SA", sa.toString());

            boolean canPlay = SpellApiToAi.Converter.get(sa).canPlayAIWithSubs(player, sa);

            Sentry.removeExtra("Card");
            Sentry.removeExtra("SA");

            if (!canPlay) {
                return AiPlayDecision.CantPlayAi;
            }
        } else {
            Cost payCosts = sa.getPayCosts();
            if (payCosts != null) {
                ManaCost mana = payCosts.getTotalMana();
                if (mana != null) {
                    if (mana.countX() > 0) {
                        final int xPay = ComputerUtilCost.getMaxXValue(sa, player, sa.isTrigger());
                        if (xPay <= 0) {
                            return AiPlayDecision.CantAffordX;
                        }
                        sa.setXManaCostPaid(xPay);
                    } else if (mana.isZero()) {
                        ManaCost cardCost = card.getManaCost();
                        if (cardCost != null && cardCost.countX() > 0) {
                            return AiPlayDecision.CantPlayAi;
                        }
                    }
                }
            }
        }
        if (checkCurseEffects(sa)) {
            return AiPlayDecision.CurseEffects;
        }
        if (!sa.isLegalAfterStack()) {
            return AiPlayDecision.AnotherTime;
        }
        Card spellHost = card;
        if (sa.isSpell()) {
            spellHost = CardCopyService.getLKICopy(spellHost);
            spellHost.setLKICMC(-1);
            spellHost.setLastKnownZone(game.getStackZone());
            spellHost.setCastFrom(card.getZone());
        }
        if (!sa.checkRestrictions(spellHost, player)) {
            return AiPlayDecision.AnotherTime;
        }
        if (sa.usesTargeting()) {
            if (!sa.isTargetNumberValid() && sa.getTargetRestrictions().getNumCandidates(sa, true) == 0) {
                return AiPlayDecision.TargetingFailed;
            }
            if (!StaticAbilityMustTarget.meetsMustTargetRestriction(sa)) {
                return AiPlayDecision.TargetingFailed;
            }
        }
        if (sa instanceof Spell) {
            if (sa.getApi() == ApiType.PermanentCreature || sa.getApi() == ApiType.PermanentNoncreature) {
                return canPlayFromEffectAI((Spell) sa, false, true);
            }
            if (!player.cantLoseForZeroOrLessLife() && player.canLoseLife() &&
                    ComputerUtil.getDamageForPlaying(player, sa) >= player.getLife()) {
                return AiPlayDecision.CurseEffects;
            }
            return canPlaySpellBasic(card, sa);
        }

        return AiPlayDecision.WillPlay;
    }

    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa) {
        return getCardsToDiscard(numDiscard, uTypes, sa, CardCollection.EMPTY);
    }

    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa, final CardCollectionView exclude) {
        boolean noFiltering = sa != null && "DiscardCMCX".equals(sa.getParam("AILogic"));
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        hand.removeAll(exclude);
        if (uTypes != null && sa != null && !noFiltering) {
            hand = CardLists.getValidCards(hand, uTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa);
        }
        return getCardsToDiscard(numDiscard, numDiscard, hand, sa);
    }

    public CardCollection getCardsToDiscard(int min, final int max, final CardCollection validCards, final SpellAbility sa) {
        if (validCards.size() <= min) {
            return validCards;
        }

        Card sourceCard = null;
        final CardCollection discardList = new CardCollection();
        int count = 0;
        if (sa != null) {
            String logic = sa.getParamOrDefault("AILogic", "");
            sourceCard = sa.getHostCard();
            if ("Always".equals(logic) && !validCards.isEmpty()) {
                min = 1;
            } else if (logic.startsWith("UnlessAtLife.")) {
                int threshold = AbilityUtils.calculateAmount(sourceCard, logic.substring(logic.indexOf(".") + 1), sa);
                if (player.getLife() <= threshold) {
                    min = 1;
                }
            } else if ("VolrathsShapeshifter".equals(logic)) {
                return SpecialCardAi.VolrathsShapeshifter.targetBestCreature(player, sa);
            } else if ("DiscardCMCX".equals(logic)) {
                final int cmc = sa.getXManaCostPaid();
                CardCollection discards = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.hasCMC(cmc));
                if (discards.isEmpty()) {
                    return null;
                }
                return new CardCollection(ComputerUtilCard.getWorstAI(discards));
            }

            if (sa.hasParam("AnyNumber")) {
                if ("DiscardUncastableAndExcess".equals(sa.getParam("AILogic"))) {
                    CardCollection discards = new CardCollection();
                    final CardCollectionView inHand = player.getCardsIn(ZoneType.Hand);
                    final int numLandsOTB = CardLists.count(inHand, CardPredicates.LANDS);
                    int numOppInHand = 0;
                    for (Player p : player.getGame().getPlayers()) {
                        if (p.getCardsIn(ZoneType.Hand).size() > numOppInHand) {
                            numOppInHand = p.getCardsIn(ZoneType.Hand).size();
                        }
                    }
                    for (Card c : inHand) {
                        if (c.hasSVar("DoNotDiscardIfAble") || c.hasSVar("IsReanimatorCard")) {
                            continue;
                        }
                        if (c.isCreature() && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getSpellPermanent(), player)) {
                            discards.add(c);
                        }
                        if ((c.isLand() && numLandsOTB >= 5) || (c.getFirstSpellAbility() != null && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getFirstSpellAbility(), player))) {
                            if (discards.size() + 1 <= numOppInHand) {
                                discards.add(c);
                            }
                        }
                    }
                    return discards;
                }
            }
        }

        while (count < min) {
            Card prefCard = null;
            if (sa != null && sa.getActivatingPlayer() != null && sa.getActivatingPlayer().isOpponentOf(player)) {
                for (Card c : validCards) {
                    if (c.hasSVar("DiscardMeByOpp")) {
                        prefCard = c;
                        break;
                    }
                }
            }
            if (prefCard == null) {
                prefCard = ComputerUtil.getCardPreference(player, sourceCard, "DiscardCost", validCards);
                if (prefCard != null && prefCard.hasSVar("DoNotDiscardIfAble")) {
                    prefCard = null;
                }
            }
            if (prefCard != null) {
                discardList.add(prefCard);
                validCards.remove(prefCard);
                count++;
            } else {
                break;
            }
        }

        final int discardsLeft = min - count;

        for (int i = 0; i < discardsLeft; i++) {
            if (validCards.isEmpty()) {
                continue;
            }
            final int numLandsInPlay = CardLists.count(player.getCardsIn(ZoneType.Battlefield), CardPredicates.LANDS_PRODUCING_MANA);
            final CardCollection landsInHand = CardLists.filter(validCards, CardPredicates.LANDS);
            final int numLandsInHand = landsInHand.size();

            boolean canDiscardLands = numLandsInHand > 3 || (numLandsInHand > 2 && numLandsInPlay > 0)
                    || (numLandsInHand > 1 && numLandsInPlay > 2) || (numLandsInHand > 0 && numLandsInPlay > 5);

            if (canDiscardLands) {
                discardList.add(landsInHand.get(0));
                validCards.remove(landsInHand.get(0));
            } else {
                CardLists.sortByCmcDesc(validCards);
                int numLandsAvailable = numLandsInPlay;
                if (numLandsInHand > 0) {
                    numLandsAvailable++;
                }

                boolean discardedUnplayable = false;
                boolean freeCastAllowed = ComputerUtilCost.isFreeCastAllowedByPermanent(player, null);

                for (int j = 0; j < validCards.size(); j++) {
                    if ((validCards.get(j).getCMC() > numLandsAvailable || freeCastAllowed) && !validCards.get(j).hasSVar("DoNotDiscardIfAble")) {
                        discardList.add(validCards.get(j));
                        validCards.remove(validCards.get(j));
                        discardedUnplayable = true;
                        break;
                    } else if (validCards.get(j).getCMC() <= numLandsAvailable) {
                        break;
                    }
                }

                if (!discardedUnplayable) {
                    Card worst = ComputerUtilCard.getWorstAI(validCards);
                    if (worst == null) {
                        worst = ComputerUtilCard.getCheapestSpellAI(validCards);
                    }
                    if (worst == null && !validCards.isEmpty()) {
                        for (Card c : validCards) {
                            if (!c.hasSVar("DoNotDiscardIfAble")) {
                                worst = c;
                                break;
                            }
                        }
                        if (worst == null) {
                            for (Card c : validCards) {
                                if (CardLists.count(player.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals(c.getName())) > 1) {
                                    worst = c;
                                    break;
                                }
                            }
                            if (worst == null) {
                                worst = Aggregates.random(validCards);
                            }
                        }
                    }
                    discardList.add(worst);
                    validCards.remove(worst);
                }
            }
        }
        return discardList;
    }

    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination) {
            System.err.printf("Overriding AI confirmAction decision for %s, defaulting to true.\n", mode);
            return true;
        }

        ApiType api = sa == null ? null : sa.getApi();

        if (sa == null || api == null) {
            String exMsg = String.format("AI confirmAction does not know what to decide about %s mode (%s is null).",
                    mode, sa == null ? "SA" : "API");
            throw new IllegalArgumentException(exMsg);
        }
        return SpellApiToAi.Converter.get(api).confirmAction(player, sa, mode, message, params);
    }

    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, int bid, Player winner) {
        if (mode != null) switch (mode) {
            case BidLife:
                if (sa.hasParam("AIBidMax")) {
                    return !player.equals(winner) && bid < Integer.parseInt(sa.getParam("AIBidMax")) && player.getLife() > bid + 5;
                }
                return false;
            default:
                return false;
        }
        return false;
    }

    public boolean confirmStaticApplication(Card hostCard, String logic) {
        return true;
    }

    public String getProperty(AiProps propName) {
        return AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);
    }

    public int getIntProperty(AiProps propName) {
        String prop = AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);

        if (prop == null || prop.isEmpty()) {
            return Integer.parseInt(propName.getDefault());
        }

        return Integer.parseInt(prop);
    }

    public boolean getBooleanProperty(AiProps propName) {
        String prop = AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);

        if (prop == null || prop.isEmpty()) {
            return Boolean.parseBoolean(propName.getDefault());
        }

        return Boolean.parseBoolean(prop);
    }

    public AiPlayDecision canPlayFromEffectAI(Spell spell, boolean mandatory, boolean withoutPayingManaCost) {
        int damage = ComputerUtil.getDamageForPlaying(player, spell);
        if (!mandatory && damage >= player.getLife() && !player.cantLoseForZeroOrLessLife() && player.canLoseLife()) {
            return AiPlayDecision.CurseEffects;
        }

        final Card card = spell.getHostCard();
        if (spell instanceof SpellApiBased) {
            boolean chance = false;
            if (withoutPayingManaCost) {
                chance = SpellApiToAi.Converter.get(spell).doTriggerNoCostWithSubs(player, spell, mandatory);
            } else {
                chance = SpellApiToAi.Converter.get(spell).doTriggerAI(player, spell, mandatory);
            }
            if (!chance) {
                return AiPlayDecision.TargetingFailed;
            }

            if (mandatory) {
                return AiPlayDecision.WillPlay;
            }

            if (card.isPermanent()) {
                if (!checkETBEffects(card, spell, null)) {
                    return AiPlayDecision.BadEtbEffects;
                }
                if (!player.cantLoseForZeroOrLessLife() && player.canLoseLife()
                        && damage + ComputerUtil.getDamageFromETB(player, card) >= player.getLife()) {
                    return AiPlayDecision.BadEtbEffects;
                }
            }
        }

        return canPlaySpellBasic(card, spell);
    }

    public void declareBlockersFor(Player defender, Combat combat) {
        AiBlockController block = new AiBlockController(defender, defender != player);
        block.assignBlockersForCombat(combat);
    }

    public void declareAttackers(Player attacker, Combat combat) {
        AiAttackController aiAtk = new AiAttackController(attacker);
        lastAttackAggression = aiAtk.declareAttackers(combat);

        aiAtk.reinforceWithBanding(combat);

        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            final Map<Card, GameEntity> legal = combat.getAttackConstraints().getLegalAttackers().getLeft();
            System.err.println("AI Attack declaration invalid, defaulting to: " + legal);
            for (final Map.Entry<Card, GameEntity> mandatoryAttacker : legal.entrySet()) {
                combat.addAttacker(mandatoryAttacker.getKey(), mandatoryAttacker.getValue());
            }
            if (!CombatUtil.validateAttackers(combat)) {
                aiAtk.declareAttackers(combat);
            }
        }

        for (final Card element : combat.getAttackers()) {
            Log.debug("Computer just assigned " + element.getName() + " as an attacker.");
            Log.info("[MJH] AI just assigned " + element.getName() + " as an attacker.");
        }
    }

    public List<SpellAbility> chooseSpellAbilityToPlay() {
        predictedCombat = null;
        predictedCombatNextTurn = null;

        memory.clearMemorySet(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);

        if (useSimulation) {
            return singleSpellAbilityList(simPicker.chooseSpellAbilityToPlay(null));
        }

        CardCollection playBeforeLand = CardLists.filter(
                player.getCardsIn(ZoneType.Hand), CardPredicates.hasSVar("PlayBeforeLandDrop")
        );
        if (!playBeforeLand.isEmpty()) {
            SpellAbility wantToPlayBeforeLand = chooseSpellAbilityToPlayFromList(
                    ComputerUtilAbility.getSpellAbilities(playBeforeLand, player), false
            );
            if (wantToPlayBeforeLand != null) {
                return singleSpellAbilityList(wantToPlayBeforeLand);
            }
        }

        CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
        if (landsWannaPlay != null) {
            landsWannaPlay = filterLandsToPlay(landsWannaPlay);
            Log.debug("Computer " + game.getPhaseHandler().getPhase().nameForUi);
            if (landsWannaPlay != null && !landsWannaPlay.isEmpty()) {
                Card land = chooseBestLandToPlay(landsWannaPlay);
                if (land != null && (!player.canLoseLife() || player.cantLoseForZeroOrLessLife() || ComputerUtil.getDamageFromETB(player, land) < player.getLife())
                        && (!game.getPhaseHandler().is(PhaseType.MAIN1) || !isSafeToHoldLandDropForMain2(land))) {
                    final List<SpellAbility> abilities = land.getAllPossibleAbilities(player, true);
                    abilities.removeIf(sa -> !sa.isLandAbility());

                    if (!abilities.isEmpty()) {
                        return abilities;
                    }
                }
            }
        }

        return singleSpellAbilityList(getSpellAbilityToPlay());
    }

    public CardCollection chooseCardsToDelve(int genericCost, CardCollection grave) {
        CardCollection toExile = new CardCollection();
        int numToExile = Math.min(grave.size(), genericCost);

        for (int i = 0; i < numToExile; i++) {
            Card chosen = null;
            for (final Card c : grave) {
                if (!c.isCreature()) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                chosen = ComputerUtilCard.getWorstCreatureAI(grave);
            }

            if (chosen == null) {
                chosen = grave.get(0);
            }

            toExile.add(chosen);
            grave.remove(chosen);
        }
        return toExile;
    }

    public boolean doTrigger(SpellAbility spell, boolean mandatory) {
        if (spell instanceof WrappedAbility)
            return doTrigger(((WrappedAbility) spell).getWrappedAbility(), mandatory);
        if (spell.getApi() != null)
            return SpellApiToAi.Converter.get(spell).doTriggerAI(player, spell, mandatory);
        if (spell.getPayCosts() == Cost.Zero && !spell.usesTargeting()) {
            return true;
        }
        return false;
    }

    public boolean aiShouldRun(final ReplacementEffect effect, final SpellAbility sa, GameEntity affected) {
        Card hostCard = effect.getHostCard();
        if (hostCard.hasAlternateState()) {
            hostCard = game.getCardState(hostCard);
        }

        if (effect.hasParam("AILogic") && effect.getParam("AILogic").equalsIgnoreCase("ProtectFriendly")) {
            final Player controller = hostCard.getController();
            if (affected instanceof Player) {
                return !((Player) affected).isOpponentOf(controller);
            }
            if (affected instanceof Card) {
                return !((Card) affected).getController().isOpponentOf(controller);
            }
        }
        if (effect.hasParam("AICheckSVar")) {
            System.out.println("aiShouldRun?" + sa);
            final String svarToCheck = effect.getParam("AICheckSVar");
            String comparator = "GE";
            int compareTo = 1;

            if (effect.hasParam("AISVarCompare")) {
                final String fullCmp = effect.getParam("AISVarCompare");
                comparator = fullCmp.substring(0, 2);
                final String strCmpTo = fullCmp.substring(2);
                try {
                    compareTo = Integer.parseInt(strCmpTo);
                } catch (final Exception ignored) {
                    if (sa == null) {
                        compareTo = AbilityUtils.calculateAmount(hostCard, hostCard.getSVar(strCmpTo), effect);
                    } else {
                        compareTo = AbilityUtils.calculateAmount(hostCard, hostCard.getSVar(strCmpTo), sa);
                    }
                }
            }

            int left = 0;

            if (sa == null) {
                left = AbilityUtils.calculateAmount(hostCard, svarToCheck, effect);
            } else {
                left = AbilityUtils.calculateAmount(hostCard, svarToCheck, sa);
            }
            System.out.println("aiShouldRun?" + left + comparator + compareTo);
            return Expressions.compare(left, comparator, compareTo);
        } else if (effect.hasParam("AICheckDredge")) {
            return player.getCardsIn(ZoneType.Library).size() > 8 || player.isCardInPlay("Laboratory Maniac");
        } else return sa != null && doTrigger(sa, false);
    }

    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        List<SpellAbility> result = Lists.newArrayList();
        for (SpellAbility sa : usableFromOpeningHand) {
            if (doTrigger(sa, false)) {
                result.add(sa);
            }
        }

        boolean hasLeyline1 = false;
        SpellAbility saGemstones = null;

        List<SpellAbility> toRemove = Lists.newArrayList();
        for (SpellAbility sa : result) {
            String srcName = sa.getHostCard().getName();
            if ("Gemstone Caverns".equals(srcName)) {
                if (saGemstones == null)
                    saGemstones = sa;
                else
                    toRemove.add(sa);
            } else if ("Leyline of Singularity".equals(srcName)) {
                if (!hasLeyline1)
                    hasLeyline1 = true;
                else
                    toRemove.add(sa);
            }
        }
        result.removeAll(toRemove);

        if (saGemstones != null) {
            result.remove(saGemstones);
            result.add(saGemstones);
        }

        return result;
    }

    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        final Card source = sa.getHostCard();
        final String logic = sa.getParamOrDefault("AILogic", "Max");
        if ("GainLife".equals(logic)) {
            if (player.getLife() < 5 || player.getCardsIn(ZoneType.Hand).size() >= player.getMaxHandSize()) {
                return min;
            }
        } else if ("LoseLife".equals(logic)) {
            if (player.getLife() > 5) {
                return min;
            }
        } else if ("Min".equals(logic)) {
            return min;
        } else if ("DigACard".equals(logic)) {
            int random = MyRandom.getRandom().nextInt(Math.min(4, max)) + 1;
            if (player.getLife() < random + 5) {
                return min;
            } else {
                return random;
            }
        } else if ("Damnation".equals(logic)) {
            int chosenMax = player.getLife() - 1;
            int cardsInPlay = player.getCardsIn(ZoneType.Battlefield).size();
            return Math.min(chosenMax, cardsInPlay);
        } else if ("OptionalDraw".equals(logic)) {
            int cardsInLib = player.getCardsIn(ZoneType.Library).size();
            if (cardsInLib >= max && player.isCardInPlay("Laboratory Maniac")) {
                return max;
            }
            int cardsInHand = player.getCardsIn(ZoneType.Hand).size();
            int maxDraw = Math.min(player.getMaxHandSize() + 2 - cardsInHand, max);
            int maxCheckLib = Math.min(maxDraw, cardsInLib);
            return Math.max(min, maxCheckLib);
        } else if ("RepeatDraw".equals(logic)) {
            int remaining = player.getMaxHandSize() - player.getCardsIn(ZoneType.Hand).size()
                    + MyRandom.getRandom().nextInt(3);
            return Math.max(remaining, min) / 2;
        } else if ("LowestLoseLife".equals(logic)) {
            return MyRandom.getRandom().nextInt(Math.min(player.getLife() / 3, player.getWeakestOpponent().getLife())) + 1;
        } else if ("HighestLoseLife".equals(logic)) {
            return Math.min(player.getLife() - 1, MyRandom.getRandom().nextInt(Math.max(player.getLife() / 3, player.getWeakestOpponent().getLife())) + 1);
        } else if ("HighestGetCounter".equals(logic)) {
            return MyRandom.getRandom().nextInt(3);
        } else if (sa.hasSVar("EnergyToPay")) {
            return AbilityUtils.calculateAmount(source, sa.getSVar("EnergyToPay"), sa);
        } else if ("Vermin".equals(logic)) {
            if (player.getLife() < 5) {
                return min;
            }

            return MyRandom.getRandom().nextInt(Math.max(player.getLife() - 5, 1));
        } else if ("SweepCreatures".equals(logic)) {
            int minAllowedChoice = AbilityUtils.calculateAmount(source, sa.getParam("Min"), sa);
            int choiceLimit = AbilityUtils.calculateAmount(source, sa.getParam("Max"), sa);
            int maxCreatures = 0;
            for (Player opp : player.getOpponents()) {
                maxCreatures = Math.max(maxCreatures, opp.getCreaturesInPlay().size());
            }
            return Math.min(choiceLimit, Math.max(minAllowedChoice, maxCreatures));
        } else if ("Random".equals(logic)) {
            return MyRandom.getRandom().nextInt((max - min) + 1) + min;
        }
        return max;
    }

    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        switch (sa.getApi()) {
            case SetLife:
                if (relatedPlayer.equals(sa.getHostCard().getController())) {
                    return Collections.max(options);
                } else if (relatedPlayer.isOpponentOf(sa.getHostCard().getController())) {
                    return Collections.min(options);
                } else {
                    return options.get(0);
                }
            case ChooseNumber:
                if (sa.getHostCard().getName().equals("Emissary's Ploy")) {
                    List<Integer> counter = Lists.newArrayList(0,0,0);
                    int max = 0;
                    int slot = 0;
                    for (Card c : relatedPlayer.getZone(ZoneType.Library).getCards()) {
                        if (!c.isCreature()) {
                            continue;
                        }

                        if (c.getCMC() > 0 && c.getCMC() < 4) {
                            counter.set(c.getCMC() - 1, counter.get(c.getCMC() - 1) + 1);
                        }
                    }
                    for(int i = 0; i < counter.size(); i++) {
                        if (counter.get(i) >= max) {
                            max = counter.get(i);
                            slot = i;
                        }
                    }
                    return slot;
                }

                return Aggregates.random(options);
            default:
                return options.get(0);
        }
    }

    public boolean confirmPayment(CostPart costPart) {
        throw new UnsupportedOperationException("AI is not supposed to reach this code at the moment");
    }

    public int attemptToAssist(SpellAbility sa, int max, int request) {
        Player activator = sa.getActivatingPlayer();

        if (game.getPlayers().size() == 2) {
            return 0;
        }

        PlayerCollection allies = player.getAllies();

        if (allies.isEmpty()) {
            return 0;
        } else {
            if (!allies.contains(activator)) {
                return 0;
            }
        }

        int mana = getAvailableManaEstimate(player, true);

        if (MyRandom.percentTrue(80)) {
            return 0;
        }

        int willingToPay = 0;
        if (mana >= request) {
            return request;
        } else {
            return mana;
        }
    }

    public CardCollection chooseCardsForEffect(CardCollectionView pool, SpellAbility sa, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        CardCollection result = new CardCollection();
        if (sa.hasParam("AIMaxAmount")) {
            max = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("AIMaxAmount"), sa);
        }
        switch (sa.getApi()) {
            case TwoPiles:
                Card biggest = null;
                Card smallest = null;
                biggest = pool.get(0);
                smallest = pool.get(0);

                for (Card c : pool) {
                    if (c.getCMC() >= biggest.getCMC()) {
                        biggest = c;
                    } else if (c.getCMC() <= smallest.getCMC()) {
                        smallest = c;
                    }
                }
                result.add(biggest);

                if (max > 3 && !result.contains(smallest)) {
                    result.add(smallest);
                }
                break;
            case MultiplePiles:
                result.addAll(pool);
                break;
            case FlipOntoBattlefield:
                if ("DamageCreatures".equals(sa.getParam("AILogic"))) {
                    int maxToughness = Integer.parseInt(sa.getSubAbility().getParam("NumDmg"));
                    CardCollectionView rightToughness = CardLists.filter(pool, card -> card.getController().isOpponentOf(sa.getActivatingPlayer())
                            && card.getNetToughness() <= maxToughness
                            && card.canBeDestroyed());
                    Card bestCreature = ComputerUtilCard.getBestCreatureAI(rightToughness.isEmpty() ? pool : rightToughness);
                    if (bestCreature != null) {
                        result.add(bestCreature);
                        break;
                    }
                } else {
                    CardCollectionView viableOptions = CardLists.filter(pool, CardPredicates.isControlledByAnyOf(sa.getActivatingPlayer().getOpponents()), CardPredicates.CAN_BE_DESTROYED);
                    Card best = ComputerUtilCard.getBestAI(viableOptions);
                    if (best != null) {
                        result.add(best);
                        break;
                    }
                }
                result.add(Aggregates.random(pool));
                break;
            default:
                CardCollection editablePool = new CardCollection(pool);
                for (int i = 0; i < max; i++) {
                    Card c = player.getController().chooseSingleEntityForEffect(editablePool, sa, null, isOptional, params);
                    if (c == null) {
                        break;
                    }
                    result.add(c);
                    editablePool.remove(c);

                    if ("BowToMyCommand".equals(sa.getParam("AILogic"))) {
                        if (!sa.getHostCard().isInZone(ZoneType.Command)) {
                            result.clear();
                            break;
                        }

                        int totPower = 0;
                        for (Card p : result) {
                            totPower += p.getNetPower();
                        }
                        if (totPower >= 8) {
                            break;
                        }
                    }
                }
        }

        if ("Phyrexian Dreadnought".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            result = SpecialCardAi.PhyrexianDreadnought.reviseCreatureSacList(player, sa, result);
        }

        return result;
    }

    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck myDeck) {
        Map<DeckSection, List<? extends PaperCard>> complaints = new HashMap<>();
        if (!useSimulation) {
            complaints = myDeck.getUnplayableAICards().unplayable;
        }
        return complaints;
    }

    public CardCollectionView cheatShuffle(CardCollectionView in) {
        if (in.size() < 20 || !canCheatShuffle()) {
            return in;
        }

        final CardCollection library = new CardCollection(in);
        CardLists.shuffle(library);

        CardCollection land = CardLists.filter(library, CardPredicates.LANDS);
        for (Card c : land) {
            if (c.isLand()) {
                library.remove(c);
            }
        }

        try {
            library.add(5, land.get(0));
            library.add(6, land.get(1));
            library.add(8, land.get(2));
            library.add(9, land.get(3));
            library.add(10, land.get(4));

            library.add(12, land.get(5));
            library.add(15, land.get(6));
        } catch (final IndexOutOfBoundsException e) {
            System.err.println("Error: cannot smooth mana curve, not enough land");
            return in;
        }

        for (Card card : land) {
            if (!library.contains(card)) {
                library.add(card);
            }
        }

        return library;
    }

    public boolean chooseDirection(SpellAbility sa) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        if ("GainControl".equals(sa.getParam("AILogic")) && game.getPlayers().size() > 2) {
            CardCollection creats = CardLists.getType(game.getCardsIn(ZoneType.Battlefield), "Creature");
            CardCollection left = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Left));
            CardCollection right = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Right));
            if (!left.isEmpty() || !right.isEmpty()) {
                CardCollection all = new CardCollection(left);
                all.addAll(right);
                return left.contains(ComputerUtilCard.getBestCreatureAI(all));
            }
        }
        if ("Aminatou".equals(sa.getParam("AILogic")) && game.getPlayers().size() > 2) {
            CardCollection all = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.NONLAND_PERMANENTS);
            CardCollection left = CardLists.filterControlledBy(all, game.getNextPlayerAfter(player, Direction.Left));
            CardCollection right = CardLists.filterControlledBy(all, game.getNextPlayerAfter(player, Direction.Right));
            return Aggregates.sum(left, Card::getCMC) > Aggregates.sum(right, Card::getCMC);
        }
        return MyRandom.getRandom().nextBoolean();
    }

    public boolean chooseEvenOdd(SpellAbility sa) {
        String aiLogic = sa.getParamOrDefault("AILogic", "");

        if (aiLogic.equals("AlwaysEven")) {
            return false;
        } else if (aiLogic.equals("AlwaysOdd")) {
            return true;
        } else if (aiLogic.equals("Random")) {
            return MyRandom.getRandom().nextBoolean();
        } else if (aiLogic.equals("CMCInHand")) {
            CardCollectionView hand = sa.getActivatingPlayer().getCardsIn(ZoneType.Hand);
            int numEven = CardLists.filter(hand, CardPredicates.evenCMC()).size();
            int numOdd = CardLists.filter(hand, CardPredicates.oddCMC()).size();
            return numOdd > numEven;
        } else if (aiLogic.equals("CMCOppControls")) {
            CardCollectionView hand = sa.getActivatingPlayer().getOpponents().getCardsIn(ZoneType.Battlefield);
            int numEven = CardLists.filter(hand, CardPredicates.evenCMC()).size();
            int numOdd = CardLists.filter(hand, CardPredicates.oddCMC()).size();
            return numOdd > numEven;
        } else if (aiLogic.equals("CMCOppControlsByPower")) {
            CardCollectionView hand = sa.getActivatingPlayer().getOpponents().getCardsIn(ZoneType.Battlefield);
            int powerEven = Aggregates.sum(CardLists.filter(hand, CardPredicates.evenCMC()), Card::getNetPower);
            int powerOdd = Aggregates.sum(CardLists.filter(hand, CardPredicates.oddCMC()), Card::getNetPower);
            return powerOdd > powerEven;
        }
        return MyRandom.getRandom().nextBoolean();
    }

    public Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                                   CardCollection fetchList, Player player2, Player decider) {
        if (useSimulation) {
            return simPicker.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }

        if (sa.getApi() == ApiType.Learn) {
            return LearnAi.chooseCardToLearn(fetchList, decider, sa);
        } else {
            return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
    }

    public List<SpellAbility> orderPlaySa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() < 2) {
            return activePlayerSAs;
        }

        List<SpellAbility> discard = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.Discard);
        List<SpellAbility> mandatoryDiscard = AiControllerAbstract.filterList(discard, SpellAbilityPredicates.isMandatory());

        List<SpellAbility> draw = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.Draw);

        List<SpellAbility> putCounter = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.PutCounter);
        List<SpellAbility> putCounterAll = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.PutCounterAll);

        List<SpellAbility> evolve = AiControllerAbstract.filterList(putCounter, CardTraitPredicates.isKeyword(Keyword.EVOLVE));

        List<SpellAbility> token = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.Token);
        List<SpellAbility> pump = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.Pump);
        List<SpellAbility> pumpAll = AiControllerAbstract.filterListByApi(activePlayerSAs, ApiType.PumpAll);

        List<SpellAbility> result = Lists.newArrayList(activePlayerSAs);

        CardCollectionView playerHand = player.getCardsIn(ZoneType.Hand);
        if (!playerHand.isEmpty() && !playerHand.anyMatch(CardPredicates.hasSVar("DiscardMe"))) {
            result.addAll(mandatoryDiscard);
            mandatoryDiscard.clear();
        }

        result.addAll(discard);
        result.addAll(draw);

        result.addAll(putCounterAll);
        result.addAll(putCounter);
        result.addAll(evolve);

        result.addAll(pumpAll);
        result.addAll(pump);
        result.addAll(token);

        result.addAll(mandatoryDiscard);

        return result;
    }

    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (simPicker != null) {
            return simPicker.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        return null;
    }

    public CardCollectionView chooseSacrificeType(String type, SpellAbility ability, boolean effect, int amount, final CardCollectionView exclude) {
        if (simPicker != null) {
            return simPicker.chooseSacrificeType(type, ability, effect, amount, exclude);
        }
        return ComputerUtil.chooseSacrificeType(player, type, ability, ability.getTargetCard(), effect, amount, exclude);
    }

    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> list) {
        if (list.size() <= 1) {
            return list.get(0);
        }

        ReplacementType mode = list.get(0).getMode();

        if (mode.equals(ReplacementType.GainLife)) {
            List<ReplacementEffect> noGain = filterListByAiLogic(list, "NoLife");
            List<ReplacementEffect> loseLife = filterListByAiLogic(list, "LoseLife");
            List<ReplacementEffect> doubleLife = filterListByAiLogic(list, "DoubleLife");
            List<ReplacementEffect> lichDraw = filterListByAiLogic(list, "LichDraw");

            if (!noGain.isEmpty()) {
                return noGain.get(0);
            } else if (!loseLife.isEmpty()) {
                return loseLife.get(0);
            } else if (!lichDraw.isEmpty()) {
                return lichDraw.get(0);
            } else if (!doubleLife.isEmpty()) {
                return doubleLife.get(0);
            }
        } else if (mode.equals(ReplacementType.DamageDone)) {
            List<ReplacementEffect> prevention = AiControllerAbstract.filterList(list, CardTraitPredicates.hasParam("Prevent"));

            if (!prevention.isEmpty()) {
                return prevention.get(0);
            }
        } else if (mode.equals(ReplacementType.Destroy)) {
            List<ReplacementEffect> shield = AiControllerAbstract.filterList(list, CardTraitPredicates.hasParam("ShieldCounter"));
            List<ReplacementEffect> regeneration = AiControllerAbstract.filterList(list, CardTraitPredicates.hasParam("Regeneration"));
            List<ReplacementEffect> umbraArmor = AiControllerAbstract.filterList(list, CardTraitPredicates.isKeyword(Keyword.UMBRA_ARMOR));
            List<ReplacementEffect> umbraArmorIndestructible = AiControllerAbstract.filterList(umbraArmor, x -> x.getHostCard().hasKeyword(Keyword.INDESTRUCTIBLE));

            if (!umbraArmorIndestructible.isEmpty()) {
                return umbraArmorIndestructible.get(0);
            }

            if (!shield.isEmpty()) {
                return shield.get(0);
            }

            if (!regeneration.isEmpty()) {
                return regeneration.get(0);
            }

            if (!umbraArmor.isEmpty()) {
                umbraArmor.sort(Comparator.comparing(CardTraitBase::getHostCard, Comparator.comparing(Card::getCMC)));
                return umbraArmor.get(0);
            }
        } else if (mode.equals(ReplacementType.Draw)) {
            List<ReplacementEffect> winGame = AiControllerAbstract.filterList(list, SpellAbility::getApi, ApiType.WinsGame);
            if (!winGame.isEmpty()) {
                return winGame.get(0);
            }
        }

        return list.get(0);
    }
}