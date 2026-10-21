package forge.mtga.forge;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Smoke test: boot Forge headless inside this module and play an AI-vs-AI game with MTGA-style decks. */
public final class ForgeSmoke {
    public static Deck deck(String name, Map<String, Integer> cards) {
        Deck d = new Deck(name);
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(e.getKey());
            if (pc == null) throw new IllegalArgumentException("Forge has no card named " + e.getKey());
            d.getOrCreate(DeckSection.Main).add(pc, e.getValue());
        }
        return d;
    }

    public static void main(String[] args) {
        File guiDir = new File(args.length > 0 ? args[0] : "forge-gui");
        GuiBase.setInterface(new HeadlessGui(guiDir));
        FModel.initialize(null, null);
        System.out.println("[smoke] Forge initialised, cards=" + StaticData.instance().getCommonCards().getAllCards().size());

        Deck d1 = deck("Green", Map.of("Forest", 16, "Llanowar Elves", 4, "Grizzly Bears", 20));
        Deck d2 = deck("Red", Map.of("Mountain", 16, "Lightning Bolt", 24));

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        List<RegisteredPlayer> players = new ArrayList<>();
        int i = 0;
        for (Deck d : List.of(d1, d2)) {
            RegisteredPlayer rp = new RegisteredPlayer(d);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("AI-" + d.getName(), i++, ""));
            players.add(rp);
        }
        Match match = new Match(rules, players, "Smoke");
        Game game = match.createGame();
        game.setNoGUIUser();
        match.startGame(game);
        System.out.println("[smoke] game over. turns=" + game.getPhaseHandler().getTurn()
                + " winner=" + (game.getOutcome().isDraw() ? "draw" : game.getOutcome().getWinningLobbyPlayer().getName()));
        System.exit(0);
    }
}
