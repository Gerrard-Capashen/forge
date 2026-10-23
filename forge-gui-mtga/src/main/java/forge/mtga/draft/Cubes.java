package forge.mtga.draft;

import forge.deck.Deck;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The cubes the server drafts: Forge's {@code res/draft/<name>.draft} files and their card lists ({@code res/cube/<name>.dck}). */
public final class Cubes {
    private Cubes() { }

    /** The premier draft cube: -Dmtga.premier.cube, else the newest "MTGO Vintage Cube" draft file (null if none). */
    public static String premierCube() {
        String file = System.getProperty("mtga.premier.cube");
        if (file != null && !file.isBlank()) return file.trim();
        String[] names = new File(ForgeConstants.DRAFT_DIR).list((d, n) -> n.startsWith("MTGO Vintage Cube ") && n.endsWith(".draft"));
        if (names == null || names.length == 0) return null;
        Arrays.sort(names);
        String newest = names[names.length - 1];
        return newest.substring(0, newest.length() - ".draft".length());
    }

    /** The cards of a cube, once each, in list order (empty if Forge has no such cube). */
    public static List<PaperCard> cards(String cube) {
        List<PaperCard> out = new ArrayList<>();
        Deck d = cube == null ? null : FModel.getDecks().getCubes().get(cube);
        if (d == null) return out;
        for (Map.Entry<PaperCard, Integer> e : d.getMain()) out.add(e.getKey());
        return out;
    }
}
