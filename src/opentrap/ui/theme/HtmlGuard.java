package opentrap.ui.theme;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns Swing's HTML rendering off for the components that would show text
 * taken from traffic (VULN-004).
 *
 * <p>Swing renders any label, button, menu, tab or tooltip text that starts
 * with {@code <html>} as HTML, and the image tags in it fetch their source
 * synchronously on the event thread. A proxied page's title or a response
 * header that starts with {@code <html>} would therefore make the tool contact
 * a host of the page's choosing, and freeze while it does. Table, list, tree
 * and combo box cells are labels, so they are covered too, as is the message
 * of an option pane.
 *
 * <p>{@link #install()} replaces the UI class of each id in {@link #UI_IDS}
 * with this class. Its {@link #createUI} sets the {@code html.disable} client
 * property, which {@code BasicHTML} honours, and then builds the real UI. The
 * guard sits above the look and feel, so it holds whichever one is active.
 *
 * <p>A {@code TitledBorder} paints its title through a label of its own and is
 * not covered; its title must never come from traffic.
 *
 * <p>A component that shows literal markup written by the application opts
 * back in with {@link #allowHtml}. The opt-in is for fixed text only, never
 * for anything that carries received data.
 */
public final class HtmlGuard {

    /** Client property that opts a component back in to HTML rendering. */
    public static final String ALLOW = "opentrap.html";

    /** The UI class ids whose components render text through BasicHTML. */
    public static final List<String> UI_IDS = List.of(
            "LabelUI", "ToolTipUI", "ButtonUI", "ToggleButtonUI", "CheckBoxUI",
            "RadioButtonUI", "MenuItemUI", "MenuUI", "CheckBoxMenuItemUI",
            "RadioButtonMenuItemUI", "TabbedPaneUI");

    private static final Map<String, String> DELEGATES = new ConcurrentHashMap<>();
    private static final Map<String, Method> FACTORIES = new ConcurrentHashMap<>();

    private HtmlGuard() {}

    /** Route the UI ids through the guard. Safe to call again. */
    public static synchronized void install() {
        String me = HtmlGuard.class.getName();
        for (String id : UI_IDS) {
            Object cur = UIManager.get(id);
            if (me.equals(cur)) continue;
            if (!(cur instanceof String name)) {
                throw new IllegalStateException("UI default " + id + " is not a class name: " + cur);
            }
            DELEGATES.put(id, name);
            FACTORIES.remove(id);
            UIManager.put(id, me);
        }
    }

    /** Called by {@code UIDefaults} for each guarded component. */
    public static ComponentUI createUI(JComponent c) {
        c.putClientProperty("html.disable",
                Boolean.TRUE.equals(c.getClientProperty(ALLOW)) ? null : Boolean.TRUE);
        String id = c.getUIClassID();
        try {
            Method m = FACTORIES.get(id);
            if (m == null) {
                String name = DELEGATES.get(id);
                if (name == null) throw new IllegalStateException("no delegate for " + id);
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                m = Class.forName(name, true, cl).getMethod("createUI", JComponent.class);
                FACTORIES.put(id, m);
            }
            return (ComponentUI) m.invoke(null, c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create the " + id + " delegate", e);
        }
    }

    /** Let {@code c} render literal {@code <html>} text written by the application. */
    public static <T extends JComponent> T allowHtml(T c) {
        c.putClientProperty(ALLOW, Boolean.TRUE);
        c.updateUI();
        return c;
    }
}
