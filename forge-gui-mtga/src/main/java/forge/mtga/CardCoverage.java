package forge.mtga;

import com.wizards.mtga.fd.FormatConfigInfo;
import com.wizards.mtga.fd.GetFormatsResponse;
import forge.StaticData;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.mtga.fd.ForgeFormats;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.HeadlessGui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Headless check of what the Front Door offers: the collection (client printings whose name Forge knows, the same
 * lookup the Front Door uses), the formats built from Forge's definitions, a few card names, and a Forge-generated
 * AI deck per format.
 *
 * <p>Args: [forgeGuiDir] [clientDir] [card names...]. Prop -Dcoverage.aidecks=Modern,Pioneer (default Modern).
 */
public final class CardCoverage {
    public static void main(String[] args) throws Exception {
        File guiDir = new File(args.length > 0 ? args[0] : ".");
        File clientDir = new File(args.length > 1 ? args[1] : "../../client");
        GuiBase.setInterface(new HeadlessGui(guiDir));
        FModel.initialize(null, null);
        CardDb db = new CardDb(CardDb.findDatabase(clientDir));

        // collection, as FrontDoor.collection() builds it
        int owned = 0;
        Map<String, List<String>> unknown = new TreeMap<>();
        for (CardDb.Printing p : db.printings()) {
            if (StaticData.instance().getCommonCards().getUniqueByName(p.name()) != null) { owned++; continue; }
            String cat = p.name().contains("//") ? "split/multi-face name" : p.digitalSet() != null && !p.digitalSet().isBlank()
                    ? "digital set " + p.digitalSet().replaceFirst("-.*", "") : "other";
            unknown.computeIfAbsent(cat, k -> new ArrayList<>()).add(p.grpId() + " " + p.name());
        }
        System.out.println("COLLECTION: " + db.printings().size() + " primary non-token printings, " + owned + " owned (Forge knows the name)");
        for (Map.Entry<String, List<String>> e : unknown.entrySet()) {
            List<String> l = e.getValue();
            System.out.println("  unknown to Forge [" + e.getKey() + "]: " + l.size() + "  e.g. " + l.subList(0, Math.min(8, l.size())));
        }

        ForgeFormats formats = new ForgeFormats(db);
        GetFormatsResponse resp = formats.response();
        System.out.println("FORMATS: " + resp.getFormatsCount() + ", GetFormats payload " + resp.getSerializedSize() + " bytes");
        for (FormatConfigInfo f : resp.getFormatsList()) {
            System.out.println("  " + f.getName() + ": allowed titles " + f.getAllowedTitleIdsCount() + ", banned " + f.getBannedTitleIdsCount()
                    + ", restricted " + f.getIndividualCardQuotasCount() + ", main " + f.getMainDeckQuota().getMin() + "-" + f.getMainDeckQuota().getMax()
                    + (f.getCardCountRestrictionInternal() == 1 ? ", singleton" : "") + (f.hasCommandZoneQuota() ? ", command zone " + f.getCommandZoneQuota().getMax() : ""));
        }

        // how much of each format the client can show at all (Forge cards legal there that have a GrpId)
        for (ForgeFormats.Spec s : ForgeFormats.SPECS) {
            if (s.forgeFormat() == null) continue;
            java.util.function.Predicate<PaperCard> rules = FModel.getFormats().getFormat(s.forgeFormat()).getFilterRules();
            int all = 0, onClient = 0;
            Map<String, List<String>> missing = new TreeMap<>();   // layout -> names without a GrpId
            for (PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                if (pc.getRules().getType().isBasicLand() || !rules.test(pc)) continue;
                all++;
                if (db.grpId(pc.getName()) != 0) onClient++;
                else missing.computeIfAbsent(pc.getRules().getSplitType().name(), k -> new ArrayList<>()).add(pc.getName());
            }
            System.out.println("FORMAT " + s.name() + ": " + onClient + " of " + all + " Forge-legal cards have a GrpId ("
                    + (all == 0 ? 0 : onClient * 100 / all) + "%)");
            for (Map.Entry<String, List<String>> e : missing.entrySet()) {
                List<String> l = e.getValue();
                System.out.println("  missing [" + e.getKey() + "] " + l.size() + ": " + l.subList(0, Math.min(12, l.size())));
            }
        }

        for (int i = 2; i < args.length; i++) {
            String n = args[i];
            PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(n);
            int grp = db.grpId(n);
            StringBuilder legalIn = new StringBuilder();
            if (grp != 0) {
                for (ForgeFormats.Spec s : ForgeFormats.SPECS) {
                    Set<Integer> t = formats.legalTitles(s.name());
                    if (t != null && t.contains(db.titleId(grp))) legalIn.append(s.name()).append(' ');
                }
            }
            System.out.println("CARD '" + n + "': forge=" + (pc != null) + " grpId=" + grp + (grp != 0 ? " title=" + db.titleId(grp) + " legal in: " + legalIn : ""));
        }

        for (String fmt : System.getProperty("coverage.aidecks", "Modern").split(",")) {
            if (fmt.isBlank()) continue;
            AiDecks.Choice c = AiDecks.forFormat(ForgeFormats.forgeFormat(fmt), n -> db.grpId(n) != 0);
            int total = c.cards().values().stream().mapToInt(Integer::intValue).sum();
            long off = c.cards().keySet().stream().filter(n -> db.grpId(n) == 0).count();
            System.out.println("AI DECK " + fmt + ": " + c.name() + ", " + total + " cards, " + off + " names without GrpId: " + c.cards());
        }
    }
}
