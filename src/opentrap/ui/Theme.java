package opentrap.ui;

import opentrap.ui.theme.*;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

/**
 * The suite's visual theme: a flat interface, light or dark, with one orange accent.
 *
 * <p>Everything here is plain Swing. The cross-platform look and feel is the
 * base, so the interface is identical on every desktop, and the parts of it
 * that look dated (bevelled buttons, boxed tabs, chunky scrollbars) are
 * replaced with flat delegates from {@code opentrap.ui.theme}. Colours and
 * fonts live in this one class so the whole interface changes together.
 */
public final class Theme {
    private Theme() {}

    // ---- palette ----
    // Not final: the theme is chosen at install() time (before any UI is built,
    // so the Ui constants that copy these capture the right values). A theme
    // change relaunches the app, exactly as a scale change does. The values come
    // from the theme files in the jar (see configure); every static non-final
    // Color, int or enum field here is a key a theme file can set.
    /** Page background. */
    public static Color BG;
    /** Raised surfaces: toolbars, table headers, cards, the status bar. */
    public static Color SURFACE;
    /** Hover and pressed state of surfaces and secondary buttons. */
    public static Color SURFACE_2;
    public static Color SURFACE_3;
    /** Hairlines and outlines. */
    public static Color BORDER;
    public static Color TEXT;
    public static Color MUTED;
    /** Link text: underlined labels that open something. */
    public static Color LINK;
    /** The accent: orange, used on primary buttons and the active tab. */
    public static Color ACCENT;
    public static Color ACCENT_DARK;
    /** Row selection: a light blue selection that stays legible against the orange accent. */
    public static Color ACCENT_LIGHT;
    /**
     * The colour of switched-on controls: check marks, radio dots, toggles,
     * filter chips, selection bars. Sampled from the reference frames, where it
     * is by far the most common saturated blue.
     */
    public static Color CONTROL;
    /** The tint behind a selected card or list row, sampled from the same frames. */
    public static Color CONTROL_TINT;
    /** The fill of an unselected segment in a segmented control. */
    public static Color SEGMENT_OFF;
    /**
     * Tab titles. The reference keeps an unselected tab near-black rather than
     * greying it out: the selected tab is marked by its underline, not by the
     * others fading. Both values are sampled from the reference frames.
     */
    public static Color TAB_TEXT;
    public static Color TAB_TEXT_ON;
    public static Color ACCENT_FAINT;

    // ---- the rest of the palette: everything a tool used to hard-code ----
    /** Cards and other raised panels that sit on the page. */
    public static Color CARD;
    /** Table headers. */
    public static Color HEADER;
    /** Strokes of small drawn icons (play, pause, dots, section glyphs). */
    public static Color ICON;
    /** The track of a switched-off toggle. */
    public static Color TOGGLE_OFF;
    public static Color SCROLL_THUMB;
    public static Color SCROLL_THUMB_HOVER;
    /** Success: a finished task, a running service. */
    public static Color OK;
    /** The search-match highlight in message views. */
    public static Color HIGHLIGHT;
    /** Message syntax colouring: header names and markup tags, the start line, JSON keys, strings, numbers. */
    public static Color CODE_NAME;
    public static Color CODE_START;
    public static Color CODE_KEY;
    public static Color CODE_STRING;
    public static Color CODE_NUMBER;
    /** Hex view: the offset column and the text column. */
    public static Color HEX_OFFSET;
    public static Color HEX_ASCII;
    /** Comparer: backgrounds of deleted, added and modified runs. */
    public static Color DIFF_DELETED;
    public static Color DIFF_ADDED;
    public static Color DIFF_MODIFIED;

    // ---- shared UI: severity, text on fills, site map, empty states ----
    /** Errors: failed requests, invalid input. */
    public static Color ERROR;
    /** The warning icon of a dialog. */
    public static Color WARN;
    /** The error icon of a dialog. */
    public static Color DIALOG_ERROR;
    /** Issue severity in tables and badges. */
    public static Color SEVERITY_HIGH;
    public static Color SEVERITY_MEDIUM;
    public static Color SEVERITY_LOW;
    public static Color SEVERITY_INFO;
    /** Issue severity in the advisory text; kept apart from SEVERITY_*, which differs today. */
    public static Color ADVISORY_HIGH;
    public static Color ADVISORY_MEDIUM;
    public static Color ADVISORY_LOW;
    public static Color ADVISORY_INFO;
    /** Text and icons on an ACCENT or ACCENT_DARK fill. */
    public static Color TEXT_ON_ACCENT;
    /** Text and icons on a CONTROL fill. */
    public static Color TEXT_ON_CONTROL;
    /** Text and icons on a selected list row (ACCENT_LIGHT). */
    public static Color TEXT_ON_SELECTION;
    /** The glyph on a round severity or dialog badge. */
    public static Color TEXT_ON_BADGE;
    /** The dark text and glyph on a badge or pill fill that white does not read on. */
    public static Color TEXT_ON_BADGE_DARK;
    /** Site map: the open padlock of a plain-text host. */
    public static Color SITE_INSECURE;
    /** Site map: the folder body. */
    public static Color SITE_FOLDER;
    /** Site map: the folder front. */
    public static Color SITE_FOLDER_LIGHT;
    /** Site map: the keyhole drawn over a padlock (alpha derived at the call site). */
    public static Color SITE_OVERLAY;
    /** Site map: the sheet of a request icon. */
    public static Color SITE_PAGE;
    /** The icon of an empty-state panel. */
    public static Color EMPTY_ICON;

    // ---- tools: decoder, discover, proxy, history, comparer, intruder ----
    /** Decoder: the colour of the URL transform in the dropdowns and on its stage. */
    public static Color DECODE_URL;
    /** Decoder: HTML entities. */
    public static Color DECODE_HTML;
    /** Decoder: Base64. */
    public static Color DECODE_BASE64;
    /** Decoder: ASCII hex. The plain hex transform uses CONTROL. */
    public static Color DECODE_HEX;
    /** Decoder: octal. */
    public static Color DECODE_OCTAL;
    /** Decoder: binary. */
    public static Color DECODE_BINARY;
    /** Decoder: gzip, deflate and brotli. */
    public static Color DECODE_COMPRESSED;
    /** Discover: the hero band's gradient, top left. */
    public static Color HERO_TOP;
    /** Discover: the hero band's gradient, bottom right. */
    public static Color HERO_BOTTOM;
    /** Discover: the diagonal line across the hero band (alpha derived at the call site). */
    public static Color HERO_GLOW;
    /** Discover: the hero headline. */
    public static Color HERO_TEXT;
    /** Discover: the hero paragraph. */
    public static Color HERO_BODY;
    /** Discover: the hero's small print. */
    public static Color HERO_MUTED;
    /** Proxy: the idle intercept illustration's beam lines. */
    public static Color INTERCEPT_BEAM;
    /** Proxy: the illustration's signal head and pole. */
    public static Color INTERCEPT_BODY;
    /** Proxy: the illustration's lighter face. */
    public static Color INTERCEPT_FACE;
    /** Proxy: the illustration's unlit lamps. */
    public static Color INTERCEPT_LAMP_OFF;
    /** Proxy: the illustration's lit lamp. */
    public static Color INTERCEPT_LAMP_ON;
    /** History highlight swatches, one per stored name (see {@link #highlight}). */
    public static Color HIGHLIGHT_RED;
    public static Color HIGHLIGHT_ORANGE;
    public static Color HIGHLIGHT_YELLOW;
    public static Color HIGHLIGHT_GREEN;
    public static Color HIGHLIGHT_CYAN;
    public static Color HIGHLIGHT_BLUE;
    public static Color HIGHLIGHT_PINK;
    public static Color HIGHLIGHT_MAGENTA;
    public static Color HIGHLIGHT_GREY;
    /** Comparer: text of a row the other list has already taken. */
    public static Color COMPARE_TAKEN;
    /** Intruder: the fill behind a payload position marker. */
    public static Color POSITION_MARK;

    /** Whether the dark palette is active (set by {@link #configure}). */
    public static boolean dark = false;

    /** Read the saved theme preference (system property wins, then prefs). */
    public static boolean darkPreferred() {
        String flag = System.getProperty("opentrap.theme");
        if (flag != null) return flag.equalsIgnoreCase("dark");
        try { return "dark".equalsIgnoreCase(
                java.util.prefs.Preferences.userRoot().node("opentrap.v1").get("ui.theme", "light")); }
        catch (Exception e) { return false; }
    }

    /** The history highlight colour for a stored name ({@code red} ... {@code grey}), or null for none or an unknown name. */
    public static Color highlight(String name) {
        if (name == null) return null;
        return switch (name) {
            case "red" -> HIGHLIGHT_RED;
            case "orange" -> HIGHLIGHT_ORANGE;
            case "yellow" -> HIGHLIGHT_YELLOW;
            case "green" -> HIGHLIGHT_GREEN;
            case "cyan" -> HIGHLIGHT_CYAN;
            case "blue" -> HIGHLIGHT_BLUE;
            case "pink" -> HIGHLIGHT_PINK;
            case "magenta" -> HIGHLIGHT_MAGENTA;
            case "grey" -> HIGHLIGHT_GREY;
            default -> null;
        };
    }

    /** The glyph or number colour for a badge, pill or tinted row on {@code fill}: white or the dark one, whichever reads better. */
    public static Color onBadge(Color fill) { return Contrast.onBadge(fill, TEXT_ON_BADGE, TEXT_ON_BADGE_DARK); }

    /** The colour with its alpha channel set to {@code alpha} (0 to 255). */
    public static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** A colour as CSS hex ({@code #RRGGBB}), for the HTML that follows the theme. */
    public static String css(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Point the palette at the built-in light or dark theme. Call before install(). */
    public static void configure(boolean darkMode) {
        try {
            ThemeFile base = ThemeKeys.builtIn(darkMode ? "dark" : "light");
            configure(ThemeResolver.resolve(base, null, ThemeKeys.spec()));
        } catch (ThemeException e) {
            throw new IllegalStateException("built-in theme is broken: " + e.getMessage(), e);
        }
    }

    /** Write every resolved value into the field of the same name. */
    public static void configure(ThemeResolver.Resolved r) {
        for (Map.Entry<String, Object> e : r.values().entrySet()) {
            try {
                Field f = Theme.class.getField(e.getKey());
                Object v = e.getValue();
                if (v instanceof String word) v = enumConstant(f.getType(), word);
                f.set(null, v);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("no theme key " + e.getKey(), ex);
            }
        }
        dark = "dark".equals(r.appearance());
    }

    private static Object enumConstant(Class<?> type, String word) {
        for (Object c : type.getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(word)) return c;
        }
        throw new IllegalStateException("no " + type.getSimpleName() + " " + word);
    }

    public static final Font FONT      = Fonts.ui(Font.PLAIN, 12);
    public static final Font FONT_BOLD = Fonts.ui(Font.BOLD, 12);

    /** Corner radius shared by buttons, fields and cards. */
    public static int RADIUS;
    /** Corner radius of cards (see {@link #cardBorder()}). */
    public static int CARD_RADIUS;
    /** Corner arc of the shape behind a hovered, flashing or pill-marked tab. */
    public static int TAB_HOVER_RADIUS;
    /** Width of the outline on fields, cards and outlined buttons, in pixels. */
    public static int LINE_WIDTH;
    /** Stroke weight of the line icons, in percent of the drawn weight. */
    public static int ICON_STROKE;
    /** Height of the selected tab's underline. */
    public static int TAB_UNDERLINE;

    /** How the selected tab is marked. */
    public enum TabMarker { UNDERLINE, PILL }
    /** How a primary button is drawn. */
    public enum PrimaryButton { FILLED, OUTLINE }
    /** Which lines a table draws between its cells. */
    public enum TableGrid { NONE, HORIZONTAL, BOTH }
    /** Whether trees draw their connecting lines. */
    public enum TreeLines { OFF, ON }

    public static TabMarker TAB_MARKER;
    public static PrimaryButton PRIMARY_BUTTON;
    public static TableGrid TABLE_GRID;
    public static TreeLines TREE_LINES;

    /** A stroke width scaled by the theme's icon stroke percentage. */
    public static float stroke(float width) {
        return width * ICON_STROKE / 100f;
    }

    /** Client property that marks a button as the primary action of its panel. */
    public static final String PRIMARY = "opentrap.primary";

    static { configure(false); }

    private static String activeId = "light";
    private static String startupNotice;

    /** The id of the theme that is active: {@code light}, {@code dark} or a user theme. */
    public static String activeId() { return activeId; }

    /** The warning to show when the chosen theme could not be loaded, or null. */
    public static String startupNotice() { return startupNotice; }

    /** Install the theme. Call once, before any component is created. */
    public static void install() {
        install(Path.of(System.getProperty("user.home"), ".opentrap", "themes"));
    }

    /**
     * Install the theme the startup rule picks (see {@link ThemeCatalog#pick}),
     * looking in {@code userThemes} for user themes. A built-in theme never
     * reads the folder.
     */
    public static void install(Path userThemes) {
        ThemeCatalog.Choice choice = ThemeCatalog.pick(new ThemeCatalog(userThemes),
            System.getProperty("opentrap.theme"),
            java.util.prefs.Preferences.userRoot().node("opentrap.v1"));
        activeId = choice.id();
        startupNotice = choice.notice();
        configure(choice.resolved());
        MetalLookAndFeel.setCurrentTheme(new Palette());
        try { UIManager.setLookAndFeel(new MetalLookAndFeel()); } catch (Exception _) {}

        UIDefaults d = UIManager.getDefaults();
        ColorUIResource bg = new ColorUIResource(BG), surface = new ColorUIResource(SURFACE),
                border = new ColorUIResource(BORDER), text = new ColorUIResource(TEXT),
                muted = new ColorUIResource(MUTED), sel = new ColorUIResource(ACCENT_LIGHT);
        FontUIResource font = new FontUIResource(FONT);

        for (String k : new String[]{"Button.font", "ToggleButton.font", "Label.font", "CheckBox.font",
                "RadioButton.font", "ComboBox.font", "TextField.font", "TextArea.font", "TabbedPane.font",
                "Table.font", "TableHeader.font", "Tree.font", "List.font", "Menu.font", "MenuItem.font",
                "MenuBar.font", "ToolTip.font", "ProgressBar.font", "TitledBorder.font", "OptionPane.font",
                "CheckBoxMenuItem.font", "RadioButtonMenuItem.font", "PopupMenu.font", "Spinner.font",
                "PasswordField.font", "EditorPane.font", "TextPane.font", "FormattedTextField.font"}) {
            d.put(k, font);
        }

        // Surfaces
        d.put("Panel.background", bg);
        d.put("Viewport.background", bg);
        d.put("ScrollPane.background", bg);
        d.put("SplitPane.background", bg);
        d.put("TabbedPane.background", bg);
        d.put("TabbedPane.contentAreaColor", bg);
        d.put("OptionPane.background", bg);
        d.put("Label.foreground", text);
        d.put("control", bg);

        // Tabs: text with an accent underline, no boxes.
        d.put("TabbedPaneUI", FlatTabbedPaneUI.class.getName());
        d.put("TabbedPane.foreground", muted);
        d.put("TabbedPane.selectedForeground", text);
        d.put("TabbedPane.tabInsets", new Insets(7, 11, 7, 11));
        d.put("TabbedPane.tabAreaInsets", new Insets(0, 6, 0, 6));
        d.put("TabbedPane.contentBorderInsets", new Insets(1, 0, 0, 0));
        d.put("TabbedPane.selectedTabPadInsets", new Insets(0, 0, 0, 0));
        d.put("TabbedPane.tabRunOverlay", 0);

        // Buttons: flat, rounded, with a filled accent variant.
        d.put("ButtonUI", FlatButtonUI.class.getName());
        d.put("ToggleButtonUI", FlatButtonUI.class.getName());
        d.put("Button.foreground", text);
        d.put("ToggleButton.foreground", text);
        d.put("Button.disabledText", muted);
        d.put("ToggleButton.disabledText", muted);
        // The padding lives in the border so the flat shape keeps its size.
        d.put("Button.border", new javax.swing.plaf.BorderUIResource.EmptyBorderUIResource(5, 12, 5, 12));
        d.put("ToggleButton.border", new javax.swing.plaf.BorderUIResource.EmptyBorderUIResource(5, 12, 5, 12));
        d.put("Button.margin", new Insets(0, 0, 0, 0));
        d.put("ToggleButton.margin", new Insets(0, 0, 0, 0));

        // Inputs
        Border fieldBorder = new RoundedBorder(BORDER, ACCENT, RADIUS, new Insets(4, 7, 4, 7));
        d.put("TextField.border", fieldBorder);
        d.put("PasswordField.border", fieldBorder);
        d.put("FormattedTextField.border", fieldBorder);
        d.put("TextField.background", bg);
        d.put("TextArea.background", bg);
        d.put("TextField.selectionBackground", sel);
        d.put("TextArea.selectionBackground", sel);
        d.put("TextField.selectionForeground", text);
        d.put("TextArea.selectionForeground", text);
        d.put("TextField.caretForeground", text);
        // Every text component, including the styled panes the message views
        // and advisories use, takes the page colours; otherwise they keep the
        // look and feel's white, which the dark theme cannot carry.
        for (String k : new String[]{"TextPane", "EditorPane", "TextArea", "TextField",
                "PasswordField", "FormattedTextField"}) {
            d.put(k + ".background", bg);
            d.put(k + ".foreground", text);
            d.put(k + ".caretForeground", text);
            d.put(k + ".selectionBackground", sel);
            d.put(k + ".selectionForeground", text);
            // A read-only viewer keeps the page colour: the request and
            // response editors are not editable, and the reference draws them
            // on white like any other page. Only a genuinely disabled field is
            // greyed, which is what disabledBackground covers.
            d.put(k + ".inactiveBackground", bg);
            d.put(k + ".inactiveForeground", text);
            d.put(k + ".disabledBackground", surface);
        }
        d.put("List.background", bg);
        d.put("List.foreground", text);
        d.put("Tree.foreground", text);
        d.put("Tree.textForeground", text);
        d.put("Tree.textBackground", bg);
        d.put("Table.focusCellForeground", text);
        d.put("Spinner.background", bg);
        d.put("Spinner.foreground", text);
        d.put("Label.disabledForeground", muted);
        d.put("ComboBox.disabledForeground", muted);
        d.put("ComboBoxUI", FlatComboBoxUI.class.getName());
        d.put("ComboBox.background", bg);
        d.put("ComboBox.foreground", text);
        d.put("ComboBox.selectionBackground", sel);
        d.put("ComboBox.selectionForeground", text);
        d.put("ComboBox.border", new RoundedBorder(BORDER, ACCENT, RADIUS, new Insets(2, 6, 2, 2)));
        d.put("ComboBox.squareButton", Boolean.FALSE);
        d.put("ComboBox.padding", new Insets(0, 2, 0, 6));
        d.put("CheckBox.icon", new CheckIcon(false));
        d.put("RadioButton.icon", new CheckIcon(true));
        d.put("CheckBox.foreground", text);
        d.put("RadioButton.foreground", text);

        // Scrollbars: thin, no arrow buttons.
        d.put("ScrollBarUI", FlatScrollBarUI.class.getName());
        d.put("ScrollBar.width", 10);
        d.put("ScrollBar.background", bg);
        d.put("ScrollBar.track", bg);

        // Tables and trees
        d.put("Table.background", bg);
        d.put("Table.foreground", text);
        d.put("Table.gridColor", border);
        d.put("Table.selectionBackground", sel);
        d.put("Table.selectionForeground", text);
        d.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder(1, 1, 1, 1));
        d.put("TableHeader.background", new ColorUIResource(HEADER));
        d.put("TableHeader.foreground", text);
        d.put("TableHeader.cellBorder", BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, BORDER), BorderFactory.createEmptyBorder(5, 6, 5, 6)));
        d.put("TableHeader.font", font);
        d.put("Tree.background", bg);
        d.put("Tree.selectionBackground", sel);
        d.put("Tree.selectionForeground", text);
        d.put("Tree.selectionBorderColor", sel);
        d.put("Tree.rowHeight", 22);
        d.put("Tree.expandedIcon", Glyphs.chevron(12, MUTED, 1));
        d.put("Tree.collapsedIcon", Glyphs.chevron(12, MUTED, 0));
        d.put("Tree.leftChildIndent", 10);
        d.put("Tree.rightChildIndent", 12);
        d.put("Tree.paintLines", TREE_LINES == TreeLines.ON);
        d.put("Tree.hash", BORDER);
        d.put("Tree.lineTypeDashed", Boolean.FALSE);
        d.put("List.selectionBackground", sel);
        d.put("List.selectionForeground", text);

        // Split panes: a plain hairline divider.
        d.put("SplitPaneUI", "javax.swing.plaf.basic.BasicSplitPaneUI");
        d.put("SplitPane.dividerSize", 6);
        d.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
        d.put("SplitPane.border", BorderFactory.createEmptyBorder());
        d.put("SplitPane.background", surface);

        // Menus
        d.put("MenuBar.gradient", null);
        d.put("MenuBar.background", bg);
        d.put("MenuBar.border", BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        MacMenuBar.installDefaults(d);
        MacKeys.installDefaults(d);
        Hotkeys.freeDefaults(d);
        d.put("Menu.background", bg);
        d.put("Menu.foreground", text);
        d.put("Menu.selectionBackground", sel);
        d.put("Menu.selectionForeground", text);
        d.put("Menu.border", BorderFactory.createEmptyBorder(4, 8, 4, 8));
        d.put("MenuItem.background", bg);
        d.put("MenuItem.selectionBackground", sel);
        d.put("MenuItem.selectionForeground", text);
        d.put("MenuItem.border", BorderFactory.createEmptyBorder(4, 10, 4, 10));
        d.put("PopupMenu.background", bg);
        d.put("PopupMenu.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(4, 0, 4, 0)));
        d.put("Separator.foreground", border);
        d.put("Separator.background", bg);

        // Progress, tooltips, dialogs
        d.put("ProgressBarUI", "javax.swing.plaf.basic.BasicProgressBarUI");
        d.put("ProgressBar.foreground", new ColorUIResource(ACCENT));
        d.put("ProgressBar.background", new ColorUIResource(SURFACE_2));
        d.put("ProgressBar.selectionForeground", bg);
        d.put("ProgressBar.selectionBackground", text);
        d.put("ProgressBar.border", BorderFactory.createEmptyBorder());
        d.put("ToolTip.background", new ColorUIResource(TEXT));
        d.put("ToolTip.foreground", bg);
        d.put("ToolTip.border", BorderFactory.createEmptyBorder(5, 8, 5, 8));
        d.put("OptionPane.messageForeground", text);
        d.put("OptionPane.informationIcon", new DialogIcon(DialogIcon.Kind.INFO));
        d.put("OptionPane.questionIcon", new DialogIcon(DialogIcon.Kind.QUESTION));
        d.put("OptionPane.warningIcon", new DialogIcon(DialogIcon.Kind.WARNING));
        d.put("OptionPane.errorIcon", new DialogIcon(DialogIcon.Kind.ERROR));
        d.put("TitledBorder.titleColor", text);
        d.put("TabbedPane.focus", new Color(0, 0, 0, 0));

        // Last, so it wraps the UIs registered above rather than being replaced by them.
        HtmlGuard.install();
    }

    /**
     * An HTML editor kit whose page colours follow the theme. A plain
     * {@code JEditorPane} renders HTML in black on white whatever the look and
     * feel says, which the dark theme cannot carry; every HTML pane (the
     * response render, advisories, issue definitions) takes this kit. A page
     * that brings its own styles still overrides these, as a render should.
     * The sheet belongs to this kit alone: {@code HTMLEditorKit.setStyleSheet}
     * would replace the app-wide default, which Swing's HTML labels and
     * buttons copy, and paint the page background behind their text.
     */
    public static javax.swing.text.html.HTMLEditorKit htmlKit() {
        javax.swing.text.html.StyleSheet ss = new javax.swing.text.html.StyleSheet();
        ss.addStyleSheet(new javax.swing.text.html.HTMLEditorKit().getStyleSheet());
        ss.addRule(String.format("body { color: #%06X; background-color: #%06X; font-family: %s; }",
                TEXT.getRGB() & 0xFFFFFF, BG.getRGB() & 0xFFFFFF, Fonts.css(Fonts.uiFamily())));
        ss.addRule(String.format("a { color: #%06X; }", ACCENT.getRGB() & 0xFFFFFF));
        ss.addRule(String.format("pre, code { color: #%06X; }", TEXT.getRGB() & 0xFFFFFF));
        return new javax.swing.text.html.HTMLEditorKit() {
            @Override public javax.swing.text.html.StyleSheet getStyleSheet() { return ss; }
        };
    }

    /** A rounded hairline border for cards and grouped content. */
    public static Border cardBorder() {
        return new RoundedBorder(BORDER, null, CARD_RADIUS, new Insets(10, 12, 10, 12));
    }

    /** Mark a button as the panel's primary action: filled with the accent. */
    public static <B extends AbstractButton> B primary(B b) {
        b.putClientProperty(PRIMARY, Boolean.TRUE);
        return b;
    }

    /** The base colours the look and feel falls back to for anything not styled here. */
    private static final class Palette extends DefaultMetalTheme {
        private final ColorUIResource p1 = new ColorUIResource(ACCENT_DARK);
        private final ColorUIResource p2 = new ColorUIResource(ACCENT);
        private final ColorUIResource p3 = new ColorUIResource(ACCENT_LIGHT);
        private final ColorUIResource s1 = new ColorUIResource(BORDER);
        private final ColorUIResource s2 = new ColorUIResource(SURFACE_2);
        private final ColorUIResource s3 = new ColorUIResource(BG);
        private final ColorUIResource black = new ColorUIResource(TEXT);
        private final FontUIResource font = new FontUIResource(FONT);
        @Override public String getName() { return "OpenTRAP"; }
        @Override protected ColorUIResource getPrimary1() { return p1; }
        @Override protected ColorUIResource getPrimary2() { return p2; }
        @Override protected ColorUIResource getPrimary3() { return p3; }
        @Override protected ColorUIResource getSecondary1() { return s1; }
        @Override protected ColorUIResource getSecondary2() { return s2; }
        @Override protected ColorUIResource getSecondary3() { return s3; }
        @Override protected ColorUIResource getBlack() { return black; }
        @Override public FontUIResource getControlTextFont() { return font; }
        @Override public FontUIResource getSystemTextFont() { return font; }
        @Override public FontUIResource getUserTextFont() { return font; }
        @Override public FontUIResource getMenuTextFont() { return font; }
        @Override public FontUIResource getWindowTitleFont() { return font; }
        @Override public FontUIResource getSubTextFont() { return font; }
    }
}
