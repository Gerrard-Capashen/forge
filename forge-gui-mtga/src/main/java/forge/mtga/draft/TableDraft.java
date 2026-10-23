package forge.mtga.draft;

import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.IBoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.item.PaperCard;
import forge.item.generation.UnOpenedProduct;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.FileUtil;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

/**
 * Forge's {@link BoosterDraft} with its product chosen without the GUI prompts of {@code generateProduct}: three packs of
 * one set (as a set's quick draft), or the packs of a cube's {@code .draft} file. Boosters are NOT opened here: the table
 * sets the human seats first ({@link #setHumanSeats}), then calls {@link #initializeBoosters()}.
 */
public final class TableDraft extends BoosterDraft {
    private TableDraft(LimitedPoolType type) {
        super(type);
    }

    /** Three boosters of a set, with the set's pick rule (some sets pick two cards at a time). */
    public static TableDraft forSet(CardEdition ed) {
        TableDraft d = new TableDraft(LimitedPoolType.Block);
        CardBlock block = new CardBlock(0, ed.getName(), List.of(ed), List.of(), ed, 3, 6);
        for (int i = 0; i < 3; i++) d.product.add(block.getBooster(ed.getCode()));
        d.setDoublePick(ed.getDraftOptions().getDoublePick());
        IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = null;
        return d;
    }

    /**
     * A cube from its {@code res/draft/<name>.draft} file: the cube's pack template and number of packs, only cards
     * {@code shown} accepts (the client must be able to draw them), singleton if the cube says so.
     */
    public static TableDraft forCube(String draftFile, Predicate<PaperCard> shown) {
        File f = new File(ForgeConstants.DRAFT_DIR, draftFile + ".draft");
        if (!f.isFile()) throw new IllegalArgumentException("no cube draft file " + f);
        CustomLimited cube = CustomLimited.parse(FileUtil.readFile(f), FModel.getDecks().getCubes());
        CardPool pool = new CardPool();
        int dropped = 0;
        for (var e : cube.getCardPool()) {
            if (shown.test(e.getKey())) pool.add(e.getKey(), e.getValue());
            else dropped += e.getValue();
        }
        if (dropped > 0) System.out.println("[mtga] draft table: cube " + cube.getName() + ": " + dropped + " cards the client cannot show are left out");
        TableDraft d = new TableDraft(LimitedPoolType.Custom);
        UnOpenedProduct packs = new UnOpenedProduct(cube.getSealedProductTemplate(), pool);
        packs.setLimitedPool(cube.isSingleton());
        for (int i = 0; i < cube.getNumPacks(); i++) d.product.add(packs);
        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(cube.getLandSetCode());
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = cube.getCustomRankingsFileName();
        return d;
    }

    /** The land set the bots build their decks with (null = Forge's default). */
    static String landSet() {
        CardEdition e = IBoosterDraft.LAND_SET_CODE[0];
        return e == null ? null : e.getCode();
    }
}
