package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.mtga.profile.Mastery;
import forge.mtga.social.MasteryService;

import java.util.List;

import static forge.mtga.fd.FrontDoor.obj;

/**
 * The mastery track as the client's own "SetMastery" campaign graph (decompiled AwsSetMasteryStrategy; findings in
 * docs/results-rank-mastery.md section 3.1): a chain of Progress nodes (one per level, Threshold = XP of the level) from
 * StartingLevelNodeId, each with a TieredReward child whose ChestDescriptions are the level's rewards per tier (Free = the top row,
 * Premium = the second row; everybody owns the premium tier: the RewardTierNodeId node is Completed);
 * RepeatingLevelNodeId is a last level that repeats forever (no reward). The level whose node is Available is the current one
 * (ProgressNodeState.CurrentProgress = XP inside it). The strategy also dereferences RewardTierNodeId's PurchaseNodeConfig.PurchaseOptions
 * and looks up CustomTokenOrbKey in the inventory, so both must exist.
 *
 * <p>Content and progress come from {@link MasteryService} (the coordinator): level n gives the track's items 2n-2 (top row) and 2n-1
 * (second row), see {@link #itemsOf}. Each reward names the cosmetic as its referenceId, so the client's hover popup shows the real thing
 * (sleeve, avatar, title, phrase); an avatar's icon is its own thumbnail (image "ForgeAvatar_&lt;id&gt;", bridge CosmeticIcons). A finished level's
 * reward node stays Available until the client claims it (GraphProcessV2 {GraphId, NodeIds}, it does so by itself on every refresh);
 * the answer's InventoryInfo carries the cosmetic and the client shows its reward popup. The grant itself was written by the coordinator
 * when the XP was paid; the claim only marks the popup as shown.
 */
final class MasteryTrack {
    static final String ORB_KEY = "ForgeMasteryOrb";
    /** The graph id must be a track the client has art for (ProgressionTracksContentController._rewardTrackData, keyed by name). */
    static final String GRAPH_ID = System.getProperty("mtga.mastery.track", "BattlePass_HOB");

    private final MasteryService mastery;
    private JsonObject definition;
    private int definitionSize = -1;

    MasteryTrack(MasteryService mastery) { this.mastery = mastery; }

    boolean enabled() { return mastery != null && mastery.hasTrack(); }

    /** Rewards per level: the top row and the second row. */
    static final int PER_LEVEL = 2;

    /** The track items a level gives (1-based level; the last level may have one). */
    static List<String[]> itemsOf(List<String[]> items, int level) {
        int from = Math.min(items.size(), (level - 1) * PER_LEVEL), to = Math.min(items.size(), level * PER_LEVEL);
        return items.subList(from, to);
    }

    /** Levels that give something (the repeating level comes after them). */
    static int rewardLevels(List<String[]> items) { return (items.size() + PER_LEVEL - 1) / PER_LEVEL; }

    synchronized JsonObject definition() {
        List<String[]> items = mastery.track();
        if (definition != null && definitionSize == items.size()) return definition;
        int levels = rewardLevels(items) + 1;                    // + the repeating level
        JsonObject nodes = new JsonObject();
        for (int i = 1; i <= levels; i++) {
            JsonArray children = new JsonArray();
            if (i < levels) {
                children.add("R" + i);
                children.add("L" + (i + 1));                     // the repeating level has no Progress child (the client walks the chain breadth-first)
                JsonObject chests = new JsonObject();
                List<String[]> its = itemsOf(items, i);
                chests.add("Free", chest(its.get(0)));
                if (its.size() > 1) chests.add("Premium", chest(its.get(1)));
                nodes.add("R" + i, obj("Id", "R" + i, "Type", "TieredReward", "Children", new JsonArray(),
                        "Configuration", obj("TieredNode", obj("ChestDescriptions", chests))));
            }
            nodes.add("L" + i, obj("Id", "L" + i, "Type", "Progress", "Children", children,
                    "Configuration", obj("ProgressNode", obj("Threshold", Mastery.LEVEL_XP))));
        }
        nodes.add("RewardTier", obj("Id", "RewardTier", "Type", "Purchase", "Children", new JsonArray(),
                "Configuration", obj("PurchaseNodeConfig", obj("PurchaseOptions", new JsonArray()))));
        JsonArray tiers = new JsonArray();
        tiers.add("Free");
        tiers.add("Premium");
        JsonObject config = obj("CustomTokenOrbKey", ORB_KEY, "OrbMappings", new JsonObject(), "RepeatingLevelNodeId", "L" + levels,
                "StartingLevelNodeId", "L1", "RewardTierNodeId", "RewardTier", "RenewalTierNodeId", null, "RewardTiers", tiers,
                "TrackEndTime", null, "PrizeWallId", null);
        definition = obj("Id", GRAPH_ID, "Type", "SetMastery", "StartTime", "2020-01-01T00:00:00Z", "EndRevealTime", "2099-01-01T00:00:00Z",
                "EndTime", "2099-01-01T00:00:00Z", "NodeGroups", new JsonArray(), "Nodes", nodes,
                "Configuration", obj("SetMasteryConfiguration", config), "Milestones", new JsonArray());
        definitionSize = items.size();
        return definition;
    }

    /**
     * A reward as the client draws it: the icon (the avatar's own thumbnail, else the generic icon of the kind), the hover popup's 3D object
     * (a sleeve face-down; the client renders the referenceId), the cosmetic's name as the title line (the phrase's own text below it for an
     * emote: the client looks it up from the referenceId) and its kind (bridge FormatLoc: "Forge/Mastery/Item/&lt;id&gt;", "Forge/Mastery/Kind_&lt;kind&gt;").
     */
    private static JsonObject chest(String[] item) {
        String kind = item[0], id = item[1];
        boolean sticker = "emote".equals(kind) && id.startsWith("Sticker_");
        // icon, hover popup object, referenceId. Every reward needs a popup object: the popup keeps the previous reward's object otherwise
        // (a sleeve stayed on screen while hovering an avatar). The card-back object renders a given sleeve (face-down). No client object
        // renders a given avatar, title, pet or sticker: those get the plain avatar object with referenceId "ForgeIcon:<image>" and the
        // bridge (RewardPopupIcons) lays that image into the popup: the avatar's own portrait, else the generic icon of the kind.
        // A phrase keeps its id (the client prints the phrase's text in the popup) on the phrase object; the bridge adds the phrase icon.
        String icon = switch (kind) {
            case "avatar" -> "ForgeAvatar_" + id;
            case "sleeve" -> "ObjectiveIcon_Sleeve_Generic";
            case "pet" -> "ObjectiveIcon_Companion_Generic";
            case "title" -> "ObjectiveIcon_Titles_Generic";
            default -> sticker ? "ObjectiveIcon_Sticker_Generic" : "ObjectiveIcon_Phrase_Generic";
        };
        String[] look = switch (kind) {
            case "sleeve" -> new String[] {icon, "RewardPopup3DIcon_PopulatedCardBack", id};
            case "emote" -> sticker ? new String[] {icon, "RewardPopup3DIcon_Avatar", "ForgeIcon:" + icon} : new String[] {icon, "RewardPopup3DIcon_MetaGoalAndPhrase", id};
            default -> new String[] {icon, "RewardPopup3DIcon_Avatar", "ForgeIcon:" + icon};
        };
        String label = "emote".equals(kind) ? (sticker ? "sticker" : "phrase") : kind;
        return obj("image1", look[0], "image2", null, "image3", null, "prefab", look[1], "referenceId", look[2],
                "headerLocKey", "Forge/Mastery/Item/" + id, "descriptionLocKey", "Forge/Mastery/Kind_" + label,
                "quantity", "1", "locParams", obj(), "availableDate", "2020-01-01T00:00:00Z");
    }

    JsonObject state() {
        int items = rewardLevels(mastery.track()), xp = mastery.xp(), done = xp / Mastery.LEVEL_XP, claimed = mastery.claimedLevel();
        int levels = items + 1;
        JsonObject ns = new JsonObject();
        for (int i = 1; i <= levels; i++) {
            boolean repeating = i == levels, complete = !repeating && done >= i, current = repeating ? done >= items : done == i - 1;
            int progress = complete ? Mastery.LEVEL_XP : current ? xp % Mastery.LEVEL_XP : 0;
            int completions = repeating ? Math.max(0, done - items) : complete ? 1 : 0;
            ns.add("L" + i, obj("Status", complete ? "Completed" : current ? "Available" : "Locked",
                    "ProgressNodeState", obj("CurrentProgress", progress, "CompletionCount", completions)));
            if (!repeating) ns.add("R" + i, obj("Status", i <= claimed ? "Completed" : complete ? "Available" : "Locked"));
        }
        ns.add("RewardTier", obj("Status", "Completed"));               // everybody has the second row
        return obj("NodeStates", ns, "MilestoneStates", new JsonObject());
    }

    /** GraphProcessV2 on reward nodes: the earned ones not yet shown become shown; their cosmetics go back as the inventory change. */
    JsonObject claim(JsonArray nodeIds) {
        List<String[]> items = mastery.track();
        int level = mastery.level(), claimed = mastery.claimedLevel(), top = claimed;
        JsonObject change = DraftEvents.inventoryChange("CampaignGraphTieredRewardNode", GRAPH_ID);
        for (JsonElement e : nodeIds) {
            String id = e.getAsString();
            if (!id.matches("R\\d+")) continue;
            int n = Integer.parseInt(id.substring(1));
            if (n <= claimed || n > level || n > rewardLevels(items)) continue;
            for (String[] item : itemsOf(items, n)) {
                change.getAsJsonArray(switch (item[0]) { case "avatar" -> "Avatars"; case "sleeve" -> "Sleeves"; case "pet" -> "Pets"; case "title" -> "Titles"; default -> "Emotes"; })
                        .add(item[1]);
            }
            change.addProperty("SourceId", GRAPH_ID + "." + id);
            top = Math.max(top, n);
        }
        if (top == claimed) return null;
        mastery.claimed(top);
        System.out.println("[mtga] mastery: rewards up to level " + top + " shown");
        JsonArray changes = new JsonArray();
        changes.add(change);
        return obj("SeqId", 0, "Changes", changes);
    }

    /** The XP of a finished match as the match report's inventory change (SeqId 0: the client applies the change only). */
    static JsonObject xpChange(int xp) {
        JsonObject change = DraftEvents.inventoryChange("EventReward", "Match");
        change.addProperty("SetMasteryProgress", xp);
        JsonArray changes = new JsonArray();
        changes.add(change);
        return obj("SeqId", 0, "Changes", changes);
    }
}
