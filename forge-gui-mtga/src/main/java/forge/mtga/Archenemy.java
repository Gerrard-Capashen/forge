package forge.mtga;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * "Archenemy: Sparky": three Brawl matches against Sparky, the AI avatar, who plays the Archenemy: the first turn and a scheme deck he sets in
 * motion at every precombat main phase. Forge's own Archenemy rules do the work ({@code GameType.Archenemy} on top of Brawl); the schemes are
 * real Forge scheme cards plus Sparky's own ({@code cardsfolder/s/sparky_*.txt}). The client shows a scheme as an emblem (StateSync).
 * The three stages, each with its own deck (resources {@code archenemy/stageN.txt}), scheme deck and life total:
 * <ol><li>Sparky: five-color good stuff, powerful but not broken;</li><li>Enraged Sparky: red all-in aggro;</li>
 * <li>Sparky Unleashed: all the power there is, with the whole scheme deck.</li></ol>
 */
public final class Archenemy {
    private Archenemy() { }

    /** The AI's avatar (the three stages share it; the bridge tints it for the later two). */
    public static final String BOSS_AVATAR = "Avatar_Basic_Sparky";

    /**
     * {@code key}: the XP prize id on the coordinator (once per player); {@code life}: Sparky's starting life (0 = the rules' 45: 40 as the
     * Archenemy + 5 from Brawl); {@code schemes}: his scheme deck (names, repeats are copies).
     */
    public record Stage(int number, String key, String name, String resource, int life, List<String> schemes) { }

    /**
     * Stage 1 gets the mild tier only: small drains and edicts, card flow, a little ramp and board. Nothing that puts a big board or a turn-one swing
     * on the table (no Wurms, Treasure bursts, 3/3-a-turn engines). The later stages unlock the harsher tiers.
     */
    private static final List<String> ONE = List.of(
            "Realms Befitting My Majesty", "Realms Befitting My Majesty", "My Tendrils Run Deep", "Because I Have Willed It", "Know Naught but Fire",
            "Know Naught but Fire", "Make Yourself Useful", "Make Yourself Useful", "Pay Tribute to Me", "Pay Tribute to Me", "Only Blood Ends Your Nightmares",
            "Perhaps You've Met My Cohort", "Sparky Opens the Library", "Sparky Recalls the Past", "The Mighty Will Fall", "I Delight in Your Convulsions",
            "I Delight in Your Convulsions", "Evil Comes to Fruition", "Evil Comes to Fruition", "Power Without Equal", "Look Skyward and Despair");

    private static final List<String> TWO = List.of(
            "Sparky Overclocks the Engine", "Sparky Overclocks the Engine", "Drench the Soil in Their Blood", "Drench the Soil in Their Blood",
            "Dark Wings Bring Your Downfall", "Dark Wings Bring Your Downfall", "Sparky Calls Down the Storm", "Sparky Calls Down the Storm",
            "Know Naught but Fire", "Know Naught but Fire", "Look Skyward and Despair", "Look Skyward and Despair",
            "Evil Comes to Fruition", "I Delight in Your Convulsions", "The Mighty Will Fall", "The Mighty Will Fall", "My Crushing Masterstroke",
            "My Crushing Masterstroke", "Sparky Cracks the Vault", "Sparky Awakens the Mana Vault", "All in Good Time");

    private static final List<String> THREE = List.of(
            "Sparky Cracks the Vault", "Sparky Calls Down the Storm", "Sparky Unleashes the Herd", "Sparky Recalls the Past",
            "Sparky Overclocks the Engine", "Sparky Opens the Library", "Sparky Awakens the Mana Vault",
            "Evil Comes to Fruition", "Look Skyward and Despair", "Behold the Power of Destruction", "The Mighty Will Fall",
            "I Delight in Your Convulsions", "Power Without Equal", "Dark Wings Bring Your Downfall", "My Forces Are Innumerable",
            "Because I Have Willed It", "My Tendrils Run Deep", "Realms Befitting My Majesty", "All in Good Time",
            "Perhaps You've Met My Cohort", "My Crushing Masterstroke", "Pay Tribute to Me", "Only Blood Ends Your Nightmares",
            "Know Naught but Fire", "Drench the Soil in Their Blood", "Make Yourself Useful",
            // the second copies of what wins games
            "Behold the Power of Destruction", "Sparky Calls Down the Storm", "Sparky Unleashes the Herd", "Look Skyward and Despair",
            "Power Without Equal", "Dark Wings Bring Your Downfall", "Evil Comes to Fruition", "Sparky Overclocks the Engine", "Sparky Opens the Library",
            "My Crushing Masterstroke", "All in Good Time");

    public static final List<Stage> STAGES = List.of(
            new Stage(1, "archenemy_1", "Sparky", "stage1", 0, ONE),
            new Stage(2, "archenemy_2", "Enraged Sparky", "stage2", 40, TWO),
            new Stage(3, "archenemy_3", "Sparky Unleashed", "stage3", 60, THREE));

/** Momir Basic's avatar (a Forge variant card; the card DB builder gives it a row like a scheme, the art server its art). */
    public static final String MOMIR_AVATAR = "Momir Vig, Simic Visionary Avatar";

    /** The real card whose art each scheme borrows (the client draws a scheme's emblem with the art of its card row): a card that suits what the scheme does. */
    private static final Map<String, String> ART = Map.ofEntries(
            Map.entry("Sparky Cracks the Vault", "Mana Vault"), Map.entry("Sparky Calls Down the Storm", "Fiery Confluence"),
            Map.entry("Sparky Unleashes the Herd", "Craterhoof Behemoth"), Map.entry("Sparky Recalls the Past", "Timetwister"),
            Map.entry("Sparky Overclocks the Engine", "Embercleave"), Map.entry("Sparky Opens the Library", "Library of Alexandria"),
            Map.entry("Sparky Awakens the Mana Vault", "Mana Crypt"), Map.entry("Evil Comes to Fruition", "Avenger of Zendikar"),
            Map.entry("Look Skyward and Despair", "Glorybringer"), Map.entry("Behold the Power of Destruction", "Wrath of God"),
            Map.entry("The Mighty Will Fall", "Sheoldred, the Apocalypse"), Map.entry("I Delight in Your Convulsions", "Necropotence"),
            Map.entry("Power Without Equal", "Ancestral Recall"), Map.entry("Dark Wings Bring Your Downfall", "Griselbrand"),
            Map.entry("My Forces Are Innumerable", "Grave Titan"), Map.entry("Because I Have Willed It", "Sol Ring"),
            Map.entry("My Tendrils Run Deep", "Gaea's Cradle"), Map.entry("Realms Befitting My Majesty", "Rampant Growth"),
            Map.entry("All in Good Time", "Time Warp"), Map.entry("Perhaps You've Met My Cohort", "Liliana of the Veil"),
            Map.entry("My Crushing Masterstroke", "Control Magic"), Map.entry("Pay Tribute to Me", "Diabolic Edict"),
            Map.entry("Only Blood Ends Your Nightmares", "Damnation"), Map.entry("Know Naught but Fire", "Fireblast"),
            Map.entry("Drench the Soil in Their Blood", "Hellrider"), Map.entry("Make Yourself Useful", "Vindicate"),
            Map.entry(MOMIR_AVATAR, "Momir Vig, Simic Visionary"));      // Momir Basic's avatar emblem borrows its creature's art

    /** The card whose art a scheme shows, null if it has none listed. */
    public static String artDonor(String scheme) { return ART.get(scheme); }

    /** Every scheme card any stage can use (one entry per name): the card DB builder gives each a GrpId. */
    public static List<String> schemeNames() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Stage s : STAGES) all.addAll(s.schemes());
        return new ArrayList<>(all);
    }

    /** Sparky's deck of a stage (1 to 3): the resource list with every card the client cannot show replaced by a basic land. */
    public static AiDecks.Choice deck(Stage stage, Predicate<String> onClient) {
        List<String> commanders = new ArrayList<>();
        List<String> basics = new ArrayList<>(List.of("Plains", "Island", "Swamp", "Mountain", "Forest"));
        Map<String, Integer> cards = new LinkedHashMap<>();
        try (InputStream in = Archenemy.class.getResourceAsStream("/archenemy/" + stage.resource() + ".txt")) {
            if (in == null) throw new IllegalStateException("archenemy/" + stage.resource() + ".txt is missing");
            boolean command = false;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.equalsIgnoreCase("Commander")) { command = true; continue; }
                if (line.equalsIgnoreCase("Deck")) { command = false; continue; }
                if (line.startsWith("Basics ")) { basics = List.of(line.substring(7).trim().split("\\s*,\\s*")); continue; }
                int sp = line.indexOf(' ');
                int n = Integer.parseInt(line.substring(0, sp));
                String name = line.substring(sp + 1).trim();
                if (command) commanders.add(name);
                else cards.merge(name, n, Integer::sum);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        int total = 0, missing = 0;
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            if (onClient.test(e.getKey())) { out.put(e.getKey(), e.getValue()); total += e.getValue(); }
            else { missing += e.getValue(); lost.add(e.getKey()); }
        }
        // missing cards and the rest up to 99 become the stage's basic lands
        int fill = Math.max(99 - total, 0);
        for (int i = 0; i < fill; i++) out.merge(basics.get(i % basics.size()), 1, Integer::sum);
        if (missing > 0) System.out.println("[mtga] " + stage.name() + "'s deck: " + missing + " cards not on the client -> basic lands " + lost);
        if (commanders.isEmpty() || !onClient.test(commanders.get(0))) throw new IllegalStateException(stage.name() + "'s commander is not on the client: " + commanders);
        return new AiDecks.Choice(stage.name(), out, null, commanders, stage.schemes(), stage.life());
    }
}
