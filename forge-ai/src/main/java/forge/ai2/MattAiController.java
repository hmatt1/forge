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
package forge.ai2;

import com.google.common.collect.Lists;
import forge.ai.*;
import forge.card.CardEdition;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.cost.CostPayLife;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.ComparatorUtil;
import forge.util.IterableUtil;
import io.sentry.Sentry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static forge.ai.AiPlayDecision.WillPlay;

public class MattAiController extends AiControllerAbstract {
    LLMApi api = new LLMApi();

    public MattAiController(final Player computerPlayer, final Game game0) {
        super(computerPlayer, game0);
    }

    @Override
    public Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) {
            return null;
        }

        landList = ComputerUtilCard.dedupeCards(landList);

        if (landList.size() == 1) {
            return landList.get(0);
        }

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);

        try {
            var response = api.chooseBestLandToPlay(game, player);
            if (response != null) {
                var possibleChoice = landList.filter(card -> card.getName().equals(response)).stream().findAny();
                if (possibleChoice.isPresent()) {
                    System.out.println("PLAYING LAND!!! " + possibleChoice.get().getName());
                    return possibleChoice.get();
                } else {
                    System.out.println("PLAYING LAND FROM PARTIAL MATCH!!! " + possibleChoice.get().getName());
                    var partialMatch = landList.filter(card -> card.getName().contains(response)).stream().findAny();
                    if (partialMatch.isPresent()) {
                        return partialMatch.get();
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        var result = super.chooseBestLandToPlay(landList);
        return result;
    }

    @Override
    public SpellAbility chooseSpellAbilityToPlayFromList(List<SpellAbility> all, boolean skipCounter) throws Exception {
        if (all == null || all.isEmpty()) {
            return null;
        }

        var playableSpellAbilityList = new ArrayList<SpellAbility>();
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            if (skipCounter && sa.getApi() == ApiType.Counter) {
                continue;
            }

            sa.setActivatingPlayer(player);
            SpellAbility root = sa.getRootAbility();

            if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                sa.setLastStateBattlefield(game.getLastStateBattlefield());
                sa.setLastStateGraveyard(game.getLastStateGraveyard());
            }

            if (canPlayAndPayFor(sa) == WillPlay) {
                playableSpellAbilityList.add(sa);
            }

            sa.clearLastState();
        }

        if (playableSpellAbilityList.isEmpty()) {
            return null;
        }

        var response = api.chooseBestSpellAbilityToPlayFromList(playableSpellAbilityList, game, player);

        if (response != null) {
            var possibleChoice = all.stream().filter(sa -> sa.getDescription().contains(response)).findAny();
            if (possibleChoice.isPresent()) {
                System.out.println("PLAYING SPELL OR ABILITY!!! " + possibleChoice.get().getDescription());
                var sa = possibleChoice.get();

                sa.setActivatingPlayer(player);
                SpellAbility root = sa.getRootAbility();

                if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                    sa.setLastStateBattlefield(game.getLastStateBattlefield());
                    sa.setLastStateGraveyard(game.getLastStateGraveyard());
                }

                AiPlayDecision opinion = canPlayAndPayFor(sa);

                sa.clearLastState();

                if (opinion != AiPlayDecision.WillPlay) {
                    return null;
                }

                return sa;
            } else {
                System.out.println("DID NOT FIND SPELL OR ABILITY TO MATCH:\n" + response);
                for (var sa : playableSpellAbilityList) {
                    System.out.println("PLAYABLE:\n" + sa.getDescription());
                }
                throw new RuntimeException("ERROR!!");
            }
        }

        return null;
    }
}
