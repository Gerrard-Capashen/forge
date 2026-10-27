package forge.mtga.profile;

import java.util.List;
import java.util.Random;

/** Nicknames for the Bot Play AI opponents, made to read like Arena screen names rather than "Forge AI". */
public final class BotNames {
    private BotNames() { }

    private static final List<String> FIRST = List.of("Alex", "Sam", "Marco", "Luca", "Nina", "Ivan", "Jules", "Mika", "Tomas", "Sofia", "Kenji",
            "Rina", "Diego", "Hana", "Oskar", "Priya", "Leo", "Maya", "Theo", "Yuki", "Felix", "Chloe", "Ravi", "Elena", "Bruno", "Zoe", "Kai",
            "Mateo", "Iris", "Anton", "Lena", "Omar", "Vera", "Dani", "Noah", "Greta", "Hugo", "Sana", "Pavel", "Mia");
    private static final List<String> NOUN = List.of("Goblin", "Wizard", "Sphinx", "Dragon", "Elf", "Merfolk", "Zombie", "Angel", "Knight", "Rogue",
            "Druid", "Shaman", "Hydra", "Phoenix", "Wurm", "Squirrel", "Bear", "Raccoon", "Cat", "Ooze", "Beast", "Vampire", "Treefolk", "Pirate");
    private static final List<String> ADJ = List.of("Sleepy", "Salty", "Tilted", "Lucky", "Grumpy", "Sneaky", "Mighty", "Tiny", "Cosmic", "Rusty",
            "Silent", "Frosty", "Crimson", "Jolly", "Feral", "Humble", "Odd", "Zen", "Brave", "Cursed", "Moody", "Swift", "Wild", "Dusty");
    private static final List<String> MTG = List.of("TolarianDropout", "ManaScrewed", "StackOverflow", "TopdeckKing", "FetchlandFan", "BoltTheBird",
            "CounterThis", "DrawGo", "PonderOnIt", "SwordsToPlow", "TapOut", "MulliganTo5", "LandfallLarry", "EndStepEnjoyer", "GoBlinGo",
            "ShockLandSam", "GrixisGrind", "SideboardPlan", "PriorityPls", "CrackTheFetch", "NoBlocksNoProblem", "ScryForLand", "FizzledSpell",
            "DoubleSpell", "TwoDropEnergy", "SummoningSick", "BlueIsBest", "RedDeckWins", "GreenStompy", "WhiteWeenie", "BlackLotus_", "UntapUpkeep");
    private static final List<String> TAG = List.of("MTG", "TCG", "Arena", "Plays", "Mains", "GG", "Magic", "Mana", "Cards", "Gaming");

    /** A nickname of at most 24 characters. */
    public static String next(Random r) {
        String n = switch (r.nextInt(8)) {
            case 0 -> pick(r, FIRST);
            case 1 -> pick(r, FIRST) + number(r);
            case 2 -> pick(r, ADJ) + pick(r, NOUN);
            case 3 -> pick(r, ADJ) + pick(r, NOUN) + number(r);
            case 4 -> pick(r, MTG);
            case 5 -> pick(r, MTG) + number(r);
            case 6 -> pick(r, FIRST) + pick(r, List.of("_", "", "x", "_x")) + pick(r, TAG);
            default -> pick(r, NOUN).toLowerCase() + pick(r, List.of("_", "", ".")) + pick(r, FIRST).toLowerCase() + (r.nextInt(3) == 0 ? number(r) : "");
        };
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    private static String number(Random r) {
        return switch (r.nextInt(3)) {
            case 0 -> String.valueOf(r.nextInt(100));
            case 1 -> String.valueOf(1980 + r.nextInt(30));      // a birth year
            default -> String.valueOf(r.nextInt(9000) + 100);
        };
    }

    private static String pick(Random r, List<String> l) { return l.get(r.nextInt(l.size())); }
}
