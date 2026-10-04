package me.zed_0xff.zb_exhume_41;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RecipeConverter using the actual game recipe files from versions/41.78 and 42.12
 * as fixtures (no copies — files are read from the versions directory at test time).
 *
 * Pairs tested (B41 name → B42 name):
 *   Make Snare Trap  → MakeSnareTrap   (trapping)
 *   Make Trap Box    → MakeTrapBox     (trapping, multi-skill)
 *   Make Cage Trap   → MakeCageTrap    (trapping)
 *   Saw Logs         → SawLogs         (carpentry)
 *   Smash Bottle     → SmashBottle     (misc)
 *   Make Stake       → n/a             (mixed tag+item, xpAward)
 */
class RecipeConverterTest {

    private static final String VERSIONS_DIR = System.getProperty(
        "versions.dir",
        System.getProperty("user.home") + "/projects/zomboid/versions"
    );

    private static String b41Recipes;
    private static String b42Trapping;
    private static String b42Carpentry;
    private static String b42Misc;

    private final RecipeConverter converter = new RecipeConverter();

    @BeforeAll
    static void loadFixtures() throws IOException {
        b41Recipes   = Files.readString(Paths.get(VERSIONS_DIR, "41.78", "win", "media", "scripts", "recipes.txt"));
        b42Trapping  = Files.readString(Paths.get(VERSIONS_DIR, "42.12", "win", "media", "scripts", "generated", "recipes", "recipes_trapping.txt"));
        b42Carpentry = Files.readString(Paths.get(VERSIONS_DIR, "42.12", "win", "media", "scripts", "generated", "recipes", "recipes_carpentry.txt"));
        b42Misc      = Files.readString(Paths.get(VERSIONS_DIR, "42.12", "win", "media", "scripts", "generated", "recipes", "recipes.txt"));
    }

    // ---- convert_file round-trip tests --------------------------------------

    @Test
    void convertFile_replacesAllRecipeKeywords_inTrappingSection() {
        // Build a module block containing only the trapping recipes from B41
        String trapping = extractModuleSection(b41Recipes, "Make Snare Trap", "Make Mattress");
        String moduleInput = "module Base\n{\n" + trapping + "}\n";

        String result = converter.convertFile(moduleInput);

        assertContainsField(result, "craftRecipe Make Snare Trap");
        assertContainsField(result, "craftRecipe Make Trap Box");
        assertContainsField(result, "craftRecipe Make Cage Trap");
        // No unconverted recipe keywords
        assertFalse(result.matches("(?s).*(?<!craft)recipe\\s+Make.*"), "should have no bare 'recipe' keyword");
    }

    @Test
    void convertFile_skipsCommentedOutRecipes() {
        // The B41 recipes.txt has HockeyMaskSmashBottle commented out right after Smash Bottle
        String chunk = extractModuleSection(b41Recipes, "Smash Bottle", "Make Stake");
        String moduleInput = "module Base\n{\n" + chunk + "}\n";

        String result = converter.convertFile(moduleInput);

        assertTrue(result.contains("craftRecipe Smash Bottle"), "Smash Bottle should be converted");
        assertFalse(result.contains("craftRecipe HockeyMaskSmashBottle"), "commented recipe should stay commented");
    }

    // ---- Make Snare Trap (SkillRequired, NeedToBeLearn, keep+tag) -----------

    @Test
    void makeSnareTrap_time_matches_b42() {
        assertEquals(
            extractFieldValue(b42Trapping, "MakeSnareTrap", "time"),
            extractFieldValue(convertB41Recipe("Make Snare Trap"), "time")
        );
    }

    @Test
    void makeSnareTrap_skillRequired_matches_b42() {
        assertEquals(
            extractFieldValue(b42Trapping, "MakeSnareTrap", "SkillRequired"),
            extractFieldValue(convertB41Recipe("Make Snare Trap"), "SkillRequired")
        );
    }

    @Test
    void makeSnareTrap_needToBeLearn_matches_b42() {
        assertEquals(
            extractFieldValue(b42Trapping, "MakeSnareTrap", "NeedToBeLearn"),
            extractFieldValue(convertB41Recipe("Make Snare Trap"), "NeedToBeLearn")
        );
    }

    @Test
    void makeSnareTrap_outputItem_matches_b42() {
        String converted = convertB41Recipe("Make Snare Trap");
        // B42: item 1 Base.TrapSnare
        assertTrue(converted.contains("item 1"), "should have output item");
        assertTrue(converted.contains("TrapSnare"), "should reference TrapSnare");
    }

    @Test
    void makeSnareTrap_sound_is_dropped_with_comment() {
        String converted = convertB41Recipe("Make Snare Trap");
        // No non-comment line should assign Sound
        assertFalse(converted.matches("(?s).*\n\\s*Sound\\s*=.*"), "Sound field should not appear as a B42 field");
        assertTrue(converted.contains("// dropped: Sound"), "Sound should appear as a dropped comment");
    }

    @Test
    void makeSnareTrap_keepSaw_maps_to_tags() {
        String converted = convertB41Recipe("Make Snare Trap");
        assertTrue(converted.contains("tags[base:saw]"), "Recipe.GetItemTypes.Saw should map to tags[base:saw]");
        assertTrue(converted.contains("mode:keep"), "keep prefix should map to mode:keep");
        assertFalse(converted.contains("SharpnessCheck"), "Saw does not require SharpnessCheck");
    }

    // ---- Make Trap Box (multi-skill SkillRequired) --------------------------

    @Test
    void makeTrapBox_multiSkill_matches_b42() {
        assertEquals(
            extractFieldValue(b42Trapping, "MakeTrapBox", "SkillRequired"),
            extractFieldValue(convertB41Recipe("Make Trap Box"), "SkillRequired")
        );
    }

    @Test
    void makeTrapBox_time_matches_b42() {
        assertEquals(
            extractFieldValue(b42Trapping, "MakeTrapBox", "time"),
            extractFieldValue(convertB41Recipe("Make Trap Box"), "time")
        );
    }

    @Test
    void makeTrapBox_inputCounts_preserved() {
        String converted = convertB41Recipe("Make Trap Box");
        assertTrue(converted.contains("item 4 [Base.Plank]"), "Plank=4 should become item 4 [Base.Plank]");
        assertTrue(converted.contains("item 7 [Base.Nails]"), "Nails=7 should become item 7 [Base.Nails]");
    }

    // ---- Make Cage Trap (single skill with trailing semicolon) --------------

    @Test
    void makeCageTrap_skillRequired_trailing_semicolon_stripped() {
        // B41: SkillRequired:Trapping=3;  →  B42: SkillRequired = Trapping:3
        assertEquals(
            extractFieldValue(b42Trapping, "MakeCageTrap", "SkillRequired"),
            extractFieldValue(convertB41Recipe("Make Cage Trap"), "SkillRequired")
        );
    }

    @Test
    void makeCageTrap_wireCount() {
        String converted = convertB41Recipe("Make Cage Trap");
        assertTrue(converted.contains("item 5 [Base.Wire]"), "Wire=5 should become item 5 [Base.Wire]");
    }

    // ---- Saw Logs (CanBeDoneFromFloor, Prop1/Prop2, AnimNode, Result=N) -----

    @Test
    void sawLogs_time_matches_b42() {
        assertEquals(
            extractFieldValue(b42Carpentry, "SawLogs", "time"),
            extractFieldValue(convertB41Recipe("Saw Logs"), "time")
        );
    }

    @Test
    void sawLogs_outputCount_matches_b42() {
        // B41: Result:Plank=3  →  converter: item 3 Base.Plank
        String converted = convertB41Recipe("Saw Logs");
        assertTrue(converted.contains("item 3 "), "Result=3 should produce item 3");
        assertTrue(converted.contains("Plank"), "output item should be Plank");
    }

    @Test
    void sawLogs_dropsCanBeDoneFromFloor_with_comment() {
        String converted = convertB41Recipe("Saw Logs");
        assertTrue(converted.contains("// dropped: CanBeDoneFromFloor"), "CanBeDoneFromFloor should be in dropped comment");
    }

    @Test
    void sawLogs_dropsProp1Prop2_with_comment() {
        String converted = convertB41Recipe("Saw Logs");
        assertTrue(converted.contains("// dropped: Prop1"), "Prop1 should appear as dropped comment");
        assertTrue(converted.contains("// dropped: Prop2"), "Prop2 should appear as dropped comment");
    }

    @Test
    void sawLogs_dropsAnimNode_with_comment() {
        String converted = convertB41Recipe("Saw Logs");
        assertTrue(converted.contains("// dropped: AnimNode"), "AnimNode should appear as dropped comment");
    }

    @Test
    void sawLogs_unparseable_xpAward_omitted() {
        // OnGiveXP:Recipe.OnGiveXP.SawLogs has no numeric suffix → no xpAward
        String converted = convertB41Recipe("Saw Logs");
        assertFalse(converted.contains("xpAward"), "SawLogs has no parseable XP amount");
    }

    // ---- Smash Bottle (alternatives, no skill) ------------------------------

    @Test
    void smashBottle_time_correctly_converted_from_b41() {
        // B41 has Time:20; B42 changed it to 30 (balance change — not a converter bug).
        // Assert the converter faithfully preserves the B41 value as an integer.
        assertEquals("20", extractFieldValue(convertB41Recipe("Smash Bottle"), "time"),
            "converter should emit B41 time value as integer");
    }

    @Test
    void smashBottle_alternatives_joined_with_semicolons() {
        String converted = convertB41Recipe("Smash Bottle");
        assertTrue(converted.contains("[Base.WineEmpty;Base.WineEmpty2;Base.WhiskeyEmpty;Base.BeerEmpty]"),
            "/ alternatives should become Base.-prefixed ; separated list");
    }

    @Test
    void smashBottle_outputItem_preserved() {
        String converted = convertB41Recipe("Smash Bottle");
        assertTrue(converted.contains("SmashedBottle"), "output item should be SmashedBottle");
    }

    // ---- Make Stake (mixed tag+item, xpAward parsing) -----------------------

    @Test
    void makeStake_xpAward_parsed_from_OnGiveXP() {
        // OnGiveXP:Recipe.OnGiveXP.WoodWork5  →  xpAward = WoodWork:5
        String converted = convertB41Recipe("Make Stake");
        assertTrue(converted.contains("xpAward = WoodWork:5"), "WoodWork5 should parse to WoodWork:5");
    }

    @Test
    void makeStake_mixedTagAndItem_mergedIntoTags() {
        // keep [Recipe.GetItemTypes.SharpKnife]/MeatCleaver
        String converted = convertB41Recipe("Make Stake");
        assertTrue(converted.contains("tags[base:sharpknife;base:meatcleaver]"), "alternatives should merge into tags[base:x;base:y]");
        assertTrue(converted.contains("mode:keep"), "keep should map to mode:keep");
        assertTrue(converted.contains("flags[SharpnessCheck]"), "SharpKnife tag should emit flags[SharpnessCheck]");
        assertFalse(converted.contains("/* also:"), "no separate comment for folded items");
    }

    // ---- Make Tin Foil Hat (whole-recipe body comparison) -------------------

    @Test
    void makeTinFoilHat_compactFormat() {
        // B41 order: ingredient, Result, Time → output order: inputs, outputs, time.
        String converted = convertB41Recipe("Make Tin Foil Hat");
        assertTrue(converted.contains("inputs  { item 1 [Base.Aluminum] }"),     "inputs should be compact single line: " + converted);
        assertTrue(converted.contains("outputs { item 1 Base.Hat_TinFoilHat }"), "outputs should be compact single line: " + converted);
        assertEquals("20", extractFieldValue(converted, "time"));
    }

    // ---- parseXpAward unit tests --------------------------------------------

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "Recipe.OnGiveXP.WoodWork5,  WoodWork:5",
        "Recipe.OnGiveXP.Cooking3,   Cooking:3",
        "Recipe.OnGiveXP.Cooking10,  Cooking:10",
        "Recipe.OnGiveXP.None,       ''",
        "Recipe.OnGiveXP.SawLogs,    ''",
    })
    void parseXpAward(String callback, String expected) {
        String result = converter.parseXpAward(callback.strip());
        String exp = expected.strip().isEmpty() ? null : expected.strip();
        assertEquals(exp, result);
    }

    // ---- helpers ------------------------------------------------------------

    /** Convert a named B41 recipe from the vanilla recipes.txt and return the full craftRecipe block. */
    private String convertB41Recipe(String recipeName) {
        String body = extractB41RecipeBody(b41Recipes, recipeName);
        assertNotNull(body, "B41 recipe not found: " + recipeName);
        return converter.convertRecipe(recipeName, body, "", "craftRecipe");
    }

    /** Extract the body (between the braces) of a B41 {@code recipe Name} block. */
    static String extractB41RecipeBody(String content, String name) {
        // Match "recipe <name>\n optional-whitespace {"
        Pattern p = Pattern.compile(
            "(?<![\\w])recipe\\s+" + Pattern.quote(name) + "\\s*\\n\\s*\\{",
            Pattern.DOTALL
        );
        Matcher m = p.matcher(content);
        if (!m.find()) return null;
        int bodyStart = m.end();
        return extractUntilClosingBrace(content, bodyStart);
    }

    /** Extract the body of a B42 {@code craftRecipe Name} block. */
    static String extractCraftRecipeBody(String content, String name) {
        Pattern p = Pattern.compile(
            "craftRecipe\\s+" + Pattern.quote(name) + "\\s*\\n\\s*\\{",
            Pattern.DOTALL
        );
        Matcher m = p.matcher(content);
        if (!m.find()) return null;
        return extractUntilClosingBrace(content, m.end());
    }

    /** Extract text from {@code from} up to the first {@code }} at the start of a line. */
    private static String extractUntilClosingBrace(String content, int from) {
        int pos = from;
        while (pos < content.length()) {
            int nl = content.indexOf('\n', pos);
            if (nl < 0) return null;
            int j = nl + 1;
            while (j < content.length() && (content.charAt(j) == ' ' || content.charAt(j) == '\t')) j++;
            if (j < content.length() && content.charAt(j) == '}') return content.substring(from, j);
            pos = nl + 1;
        }
        return null;
    }

    /**
     * Extract the lines from B41 recipes.txt that span between the first occurrence of
     * {@code startRecipe} and the line before {@code endRecipe}, so tests get a self-contained chunk.
     */
    private static String extractModuleSection(String content, String startRecipe, String endRecipe) {
        int start = content.indexOf("recipe " + startRecipe);
        if (start < 0) throw new IllegalArgumentException("Recipe not found: " + startRecipe);
        int end = content.indexOf("recipe " + endRecipe, start);
        if (end < 0) throw new IllegalArgumentException("End recipe not found: " + endRecipe);
        return content.substring(start, end);
    }

    /**
     * Extract the value of {@code fieldName = <value>} from a recipe block (B42 syntax).
     * Returns null when not present.
     */
    static String extractFieldValue(String recipeBlock, String fieldName) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\s*=\\s*([^,\n]+)");
        Matcher m = p.matcher(recipeBlock);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * Variant that searches inside a named craftRecipe block within a larger file.
     */
    static String extractFieldValue(String content, String craftRecipeName, String fieldName) {
        String body = extractCraftRecipeBody(content, craftRecipeName);
        assertNotNull(body, "craftRecipe not found: " + craftRecipeName);
        return extractFieldValue(body, fieldName);
    }

    private static void assertContainsField(String text, String expected) {
        assertTrue(text.contains(expected), "expected to find: " + expected + "\nin:\n" + text);
    }

    /**
     * Extract the body of a {@code craftRecipe Name} block using brace counting,
     * so nested {@code inputs}/{@code outputs} sub-blocks are included correctly.
     */
    static String extractCraftRecipeBodyFull(String content, String name) {
        Pattern p = Pattern.compile("craftRecipe\\s+" + Pattern.quote(name) + "\\s*\\n\\s*\\{", Pattern.DOTALL);
        Matcher m = p.matcher(content);
        if (!m.find()) return null;
        int depth = 1, i = m.end(), bodyStart = m.end();
        while (i < content.length() && depth > 0) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return content.substring(bodyStart, i); }
            i++;
        }
        return null;
    }

    /**
     * Strip lines whose first non-whitespace token is a B42-only field not producible by the converter:
     * {@code timedAction}, {@code Tags}, {@code AutoLearnAny}, {@code category}, {@code flags[}.
     */
    static String stripB42OnlyFields(String body) {
        return Arrays.stream(body.split("\n"))
            .filter(line -> {
                String s = line.strip();
                return !s.startsWith("timedAction") && !s.startsWith("Tags") &&
                       !s.startsWith("AutoLearnAny") && !s.startsWith("category") && !s.startsWith("flags[");
            })
            .collect(Collectors.joining("\n"));
    }

    /** Strip leading/trailing whitespace on each line and drop blank lines for indentation-agnostic comparison. */
    static String normalizeBody(String body) {
        return Arrays.stream(body.split("\n"))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining("\n"));
    }
}
