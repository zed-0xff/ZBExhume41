package me.zed_0xff.zb_exhume_41;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import me.zed_0xff.zombie_buddy.Reflect;

/**
 * Converts B41 {@code recipe} script blocks to B42 {@code craftRecipe} format.
 *
 * <p>Converts syntax and structure only. Fields with no B42 equivalent are dropped
 * with a {@code // dropped:} comment. New B42-only fields (timedAction, Tags, flags, etc.)
 * are not synthesized — they must be added manually after conversion.
 */
public class RecipeConverter {

    // ---- data types ---------------------------------------------------------

    private enum Mode { KEEP, DESTROY }
    private enum ItemKind { TAG, ITEM }

    private record Item(ItemKind kind, String value) {}
    private record Ingredient(Mode mode, int count, List<Item> items) {}

    // ---- constants ----------------------------------------------------------

    private static final Map<String, String> SCRIPT_TYPES = Map.of(
        "recipe", "craftRecipe"
    );

    private static final Set<String> KNOWN_KEYS = Set.of(
            // evolvedrecipe
            "AddIngredientIfCooked",
            "AddIngredientSound",
            "BaseItem",
            "CanAddSpicesEmpty",
            "Cookable",
            "MaxItems",
            "Name",
            "ResultItem",
            "Template"
    );

    private static final Set<String> PROPERTY_KEYS = Set.of(

            // recipe -> craftRecipe
            "AllowFrozenItem", 
            "AllowRottenItem",
            "AnimNode", 
            "CanBeDoneFromFloor",
            "Category", 
            "NeedToBeLearn",
            "OnCanPerform", 
            "OnCreate", 
            "OnGiveXP", 
            "OnTest",
            "Prop1", 
            "Prop2", 
            "Result",
            "SkillRequired", 
            "Sound", 
            "Time",
            "Tooltip"
    );

    /** Property keys that have no B42 equivalent and are emitted as comments. */
    private static final Set<String> DROPPED_KEYS = Set.of(
        "Sound", "AnimNode", "AllowFrozenItem", "Tooltip", "CanBeDoneFromFloor", "Prop1", "Prop2"
    );

    /** Matches a block-comment start OR a B41 recipe header line. */
    private static final Pattern FILE_PATTERN = Pattern.compile(
        "/\\*|(?<!\\w)(\\w*recipe)\\s+([^\\n{]+?)\\s*\\n",
        Pattern.DOTALL
    );

    private static final Pattern PROPERTY_LINE     = Pattern.compile("^(\\w+)\\s*:(.*)$");
    private static final Pattern RESULT_WITH_COUNT = Pattern.compile("^(.+?)=(\\d+)$");
    private static final Pattern TRAILING_COUNT    = Pattern.compile("[=;](\\d+)$");
    private static final Pattern XP_SUFFIX         = Pattern.compile("^([A-Za-z]+?)(\\d+)$");

    // ---- configuration ------------------------------------------------------

    /** When true, single-item inputs/outputs blocks are emitted on one line. Default: true. */
    public boolean compact = true;

    private boolean allowRottenItem; // reset per recipe in convertRecipe

    // ---- public API ---------------------------------------------------------

    /**
     * Convert all {@code recipe} blocks in a B41 script file.
     * Block comments (including nested) are skipped unchanged.
     */
    public String convertFile(String content) {
        content = content.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder result = new StringBuilder();
        int pos = 0;
        Matcher m = FILE_PATTERN.matcher(content);

        while (m.find(pos)) {
            if (m.group().startsWith("/*")) {
                result.append(content, pos, m.start());
                int closePos = findCommentClose(content, m.end());
                if (closePos < 0) {
                    result.append(content.substring(m.start()));
                    return result.toString();
                }
                result.append(content, m.start(), closePos + 2);
                pos = closePos + 2;
            } else {
                String in_type = m.group(1);
                String out_type = SCRIPT_TYPES.getOrDefault(in_type, in_type);

                String name = m.group(2).trim();
                int after = m.end();

                // Append up to the start of the recipe's line (not including its leading whitespace);
                // convertRecipe re-emits the correct indent via the closing-brace indent.
                int lineStart = m.start();
                while (lineStart > pos && content.charAt(lineStart - 1) != '\n') lineStart--;
                result.append(content, pos, lineStart);

                int openBrace = content.indexOf('{', after);
                if (openBrace < 0) {
                    result.append(out_type).append(" ").append(name).append("\n");
                    pos = after;
                    continue;
                }

                int bodyStart = openBrace + 1;
                int closingBrace = findRecipeClose(content, bodyStart);
                if (closingBrace < 0) {
                    result.append(out_type).append(" ").append(name).append("\n{");
                    pos = bodyStart;
                    continue;
                }

                String body = content.substring(bodyStart, closingBrace);
                String indent = extractLineIndent(content, closingBrace);
                result.append(convertRecipe(name, body, indent, out_type));
                pos = closingBrace + 1;
            }
        }
        result.append(content.substring(pos));
        return result.toString();
    }

    /**
     * Convert a single B41 recipe body (the text between the outer braces).
     *
     * @param name   recipe name (without the {@code recipe} keyword)
     * @param body   raw text of the recipe body (everything between { and })
     * @param indent leading whitespace for the outer block (matches the closing-brace indent)
     */
    public String convertRecipe(String name, String body, String indent, String out_type) {
        String p = indent + "    ";

        HashMap<String, String> props = new HashMap<>();
        ArrayList<String> nonPropLines = new ArrayList<>();

        for (String raw : body.strip().split("\n")) {
            String l = raw.strip();
            if (l.endsWith(",")) l = l.substring(0, l.length() - 1).strip();
            if (l.isEmpty()) continue;

            Matcher pm = PROPERTY_LINE.matcher(l);
            if (pm.matches()) {
                props.put(pm.group(1), pm.group(2).strip());
            } else {
                nonPropLines.add(raw);
            }
        }
        allowRottenItem = "true".equalsIgnoreCase(props.get("AllowRottenItem"));

        List<String> frags = new ArrayList<>();
        for (String raw : nonPropLines) frags.add(convertLine(raw));

        List<String> ingredientFrags = frags.stream()
            .filter(f -> f != null && f.startsWith("item "))
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(out_type).append(" ").append(name).append("\n");
        sb.append(indent).append("{\n");

        boolean inputsEmitted = false;
        for (String frag : frags) {
            if (frag == null) continue;
            if (frag.isEmpty()) { sb.append("\n"); continue; }
            if (frag.startsWith("item ")) {
                if (!inputsEmitted) {
                    inputsEmitted = true;
                    sb.append(formatInputsBlock(ingredientFrags, p)).append("\n");
                }
            } else {
                sb.append(p).append(frag).append("\n");
            }
        }
        if (props.containsKey("Result")) {
            sb.append(p).append(formatResult(props.get("Result"))).append("\n");
            props.remove("Result");
        }
        for (var entry : props.entrySet()) {
            String propFrag = formatProp(entry.getKey(), entry.getValue());
            if (propFrag != null) sb.append(p).append(propFrag).append("\n");
        }

        sb.append(indent).append("}");
        return sb.toString();
    }

    /** Convert one B41 recipe body line to a B42 fragment (no leading indent, no trailing newline).
     *  Returns {@code null} to skip the line, {@code ""} to emit a blank line. */
    public String convertLine(String b41Line) {
        String line = b41Line.strip();
        if (line.endsWith(",")) line = line.substring(0, line.length() - 1).strip();
        if (line.startsWith("//")) return null;
        if (line.isEmpty()) return "";

        Matcher pm = PROPERTY_LINE.matcher(line);
        if (pm.matches() && PROPERTY_KEYS.contains(pm.group(1)))
            return formatProp(pm.group(1), pm.group(2).strip());

        Ingredient ing = parseIngredient(line);
        return ing != null ? ingredientToB42(ing) : null;
    }

    // ---- parsing ------------------------------------------------------------

    private Ingredient parseIngredient(String line) {
        if (line.isBlank()) return null;

        Mode mode = Mode.DESTROY;
        String rest = line;

        if (rest.startsWith("keep ")) {
            mode = Mode.KEEP;
            rest = rest.substring(5).strip();
        } else if (rest.startsWith("destroy ")) {
            rest = rest.substring(8).strip();
        }

        int count = 1;
        Matcher cm = TRAILING_COUNT.matcher(rest);
        if (cm.find()) {
            count = Integer.parseInt(cm.group(1));
            rest = rest.substring(0, cm.start()).strip();
        }

        List<Item> items = new ArrayList<>();
        for (String part : splitAlternatives(rest)) {
            Item item = parseItemRef(part.strip());
            if (item != null) items.add(item);
        }

        return items.isEmpty() ? null : new Ingredient(mode, count, items);
    }

    /** Split {@code expr} by {@code /} while respecting {@code [...]} brackets. */
    private List<String> splitAlternatives(String expr) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char c : expr.toCharArray()) {
            if (c == '[') { depth++; cur.append(c); }
            else if (c == ']') { depth--; cur.append(c); }
            else if (c == '/' && depth == 0) {
                String part = cur.toString().strip();
                if (!part.isEmpty()) parts.add(part);
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().strip();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }

    private Item parseItemRef(String ref) {
        if (ref.isEmpty()) return null;
        if (ref.startsWith("[") && ref.endsWith("]")) {
            String inner = ref.substring(1, ref.length() - 1);
            if (inner.startsWith("Recipe.GetItemTypes.")) {
                return new Item(ItemKind.TAG, "base:" + inner.substring("Recipe.GetItemTypes.".length()).toLowerCase());
            }
            return new Item(ItemKind.ITEM, inner);
        }
        return new Item(ItemKind.ITEM, ref);
    }

    // ---- emit B42 -----------------------------------------------------------

    private String formatInputsBlock(List<String> frags, String p) {
        if (frags.isEmpty()) return p + "inputs { }";
        String open = "inputs  { ";
        if (frags.size() == 1 && compact) return p + open + frags.get(0) + " }";

        String align = p + " ".repeat(open.length());
        StringBuilder sb = new StringBuilder();
        sb.append(p).append(open).append(frags.get(0)).append(",");
        for (int i = 1; i < frags.size(); i++) {
            sb.append("\n").append(align).append(frags.get(i));
            sb.append(i < frags.size() - 1 ? "," : " }");
        }
        return sb.toString();
    }

    private String formatResult(String value) {
        Matcher rm = RESULT_WITH_COUNT.matcher(value);
        String item = rm.matches() ? "item " + rm.group(2) + " " + namespacedItem(rm.group(1)) : "item 1 " + namespacedItem(value);
        return compact ? "outputs { " + item + " }" : "outputs\n{\n    " + item + "\n}";
    }

    private String formatProp(String key, String value) {
        return switch (key) {
            case "Time"          -> "time = " + (int) Double.parseDouble(value) + ",";
            case "Category"      -> "category = " + value + ",";
            case "SkillRequired" -> "SkillRequired = " + convertSkillRequired(value) + ",";
            case "NeedToBeLearn" -> "NeedToBeLearn = " + value + ",";
            case "OnCreate"      -> { String oc = convertOnCreate(value); yield oc != null ? "OnCreate = " + oc + "," : "// dropped: OnCreate:" + value; }
            case "OnGiveXP"      -> { String a = parseXpAward(value); yield a != null ? "xpAward = " + a + "," : null; }
            case "OnCanPerform", "OnTest" -> "OnTest = " + value + ",";
            case "Result"        -> formatResult(value);
            case "AllowRottenItem" -> null; // applied as flags[AllowRottenItem] on consumed items
            default -> DROPPED_KEYS.contains(key)
                ? ("// dropped: " + key + ":" + value) 
                : KNOWN_KEYS.contains(key)
                    ? (key + " = " + (key.endsWith("Item") ? namespacedItem(value) : value) + ",")
                    : ("// unknown: " + key + ":" + value);
        };
    }

    private String ingredientToB42(Ingredient ing) {
        List<Item> tags  = ing.items().stream().filter(i -> i.kind() == ItemKind.TAG).toList();
        List<Item> items = ing.items().stream().filter(i -> i.kind() == ItemKind.ITEM).toList();
        String mode   = ing.mode() == Mode.KEEP ? " mode:keep" : "";
        String prefix = "item " + ing.count() + " ";

        boolean consumed = ing.mode() != Mode.KEEP;
        if (tags.isEmpty()) {
            String flags = (allowRottenItem && consumed) ? " flags[AllowRottenItem]" : "";
            return prefix + "[" + items.stream().map(i -> namespacedItem(i.value())).collect(Collectors.joining(";")) + "]" + mode + flags;
        }
        // Any tag present: fold all alternatives (tags + plain items) into tags[base:x;...].
        // Plain items are treated as base-namespace tags since they co-appear with GetItemTypes refs.
        List<String> allTags = new ArrayList<>();
        tags.forEach(t  -> allTags.add(t.value()));
        items.forEach(i -> allTags.add("base:" + i.value().toLowerCase()));

        List<String> flagList = new ArrayList<>();
        if (allTags.contains("base:sharpknife"))  flagList.add("SharpnessCheck");
        if (allowRottenItem && consumed)          flagList.add("AllowRottenItem");
        String flags = flagList.isEmpty() ? "" : " flags[" + String.join(";", flagList) + "]";
        return prefix + "tags[" + String.join(";", allTags) + "]" + mode + flags;
    }

    private static String namespacedItem(String item) {
        return item.contains(".") ? item : "Base." + item;
    }

    // B41: Woodwork=1;Trapping=2; → B42: Woodwork:1;Trapping:2
    private String convertSkillRequired(String value) {
        StringBuilder sb = new StringBuilder();
        for (String pair : value.split(";")) {
            pair = pair.strip();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(pair, 0, eq).append(':').append(pair.substring(eq + 1));
        }
        return sb.toString();
    }

    /** Parse B41 OnGiveXP callback to a B42 xpAward value, or return null if not recognisable. */
    String parseXpAward(String callback) {
        if (callback == null) return null;
        String[] parts = callback.split("\\.");
        String last = parts[parts.length - 1];
        if ("None".equals(last)) return null;
        Matcher m = XP_SUFFIX.matcher(last);
        return m.matches() ? m.group(1) + ":" + m.group(2) : null;
    }

    // ---- helpers ------------------------------------------------------------

    /** Find the closing {@code *}{@code /} for a block comment, respecting nesting. */
    private int findCommentClose(String s, int from) {
        int depth = 1, i = from;
        while (i < s.length() - 1) {
            char c0 = s.charAt(i), c1 = s.charAt(i + 1);
            if (c0 == '/' && c1 == '*') { depth++; i += 2; }
            else if (c0 == '*' && c1 == '/') { if (--depth == 0) return i; i += 2; }
            else i++;
        }
        return -1;
    }

    /** Find the first {@code }} that appears at the beginning of a line (after optional whitespace). */
    private int findRecipeClose(String s, int from) {
        int pos = from;
        while (pos < s.length()) {
            int nl = s.indexOf('\n', pos);
            if (nl < 0) return -1;
            int j = nl + 1;
            while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
            if (j < s.length() && s.charAt(j) == '}') return j;
            pos = nl + 1;
        }
        return -1;
    }

    /** Return the whitespace characters that precede the character at {@code pos} on its line. */
    private String extractLineIndent(String s, int pos) {
        int lineStart = pos - 1;
        while (lineStart >= 0 && s.charAt(lineStart) != '\n') lineStart--;
        lineStart++;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < pos; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }
        return indent.toString();
    }

    private static final Map<String, String> ON_CREATE_OVERRIDES = Map.of(
        "DblBarrelhotgunSawnoff_OnCreate", "RecipeCodeOnCreate.shotgunSawnoff"
    );

    /**
     * Map B41 OnCreate callbacks to their B42 equivalents.
     * Returns {@code null} if the target method does not exist in {@code RecipeCodeOnCreate}.
     */
    private static String convertOnCreate(String value) {
        String b42;
        String override = ON_CREATE_OVERRIDES.get(value);
        if (override != null) {
            b42 = override;
        } else if (value.endsWith("_OnCreate")) {
            String name = value.substring(0, value.length() - "_OnCreate".length());
            b42 = "RecipeCodeOnCreate." + Character.toLowerCase(name.charAt(0)) + name.substring(1);
        } else {
            return value;
        }
        return verifyOnCreateMethod(b42) ? b42 : null;
    }

    private static boolean verifyOnCreateMethod(String b42Value) {
        int dot = b42Value.lastIndexOf('.');
        if (dot < 0) return true;
        String methodName = b42Value.substring(dot + 1);
        try {
            Reflect r = Reflect.on("zombie.scripting.logic.RecipeCodeOnCreate");
            if (!r.isPresent()) return true;
            return r.methods().stream()
                .anyMatch(m -> m.getName().equals(methodName) && Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()));
        } catch (NoClassDefFoundError | Exception e) {
            return true; // running outside PZ — skip check
        }
    }
}
