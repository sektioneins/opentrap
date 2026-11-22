package opentrap.proxy;

/**
 * The rewrites behind the proxy toolbar's response toggles (unhide hidden
 * fields, enable disabled fields, remove length limits, JavaScript validation,
 * object tags and all JavaScript). They run on every HTML response while a
 * toggle is on, whatever the server sent, so each must stay linear in the
 * length of the body.
 */
final class HtmlRewrite {

    private HtmlRewrite() {}

    static String unhideHiddenFields(String html) {
        return html.replaceAll("(?i)type\\s*=\\s*([\"']?)hidden\\1", "type=$1text$1");
    }

    static String enableDisabledFields(String html) {
        return html.replaceAll("(?i)\\s+disabled(\\s*=\\s*[\"']?[^\"'>\\s]*[\"']?)?", "");
    }

    static String removeLengthLimits(String html) {
        return html.replaceAll("(?i)\\s+maxlength\\s*=\\s*[\"']?\\d+[\"']?", "");
    }

    static String removeJsValidation(String html) {
        return html.replaceAll("(?i)\\s+onsubmit\\s*=\\s*\"[^\"]*\"", "");
    }

    static String removeObjectTags(String html) {
        return html.replaceAll("(?is)<object.*?</object>", "").replaceAll("(?i)<embed[^>]*>", "");
    }

    static String removeAllJavaScript(String html) {
        String b = html.replaceAll("(?is)<script.*?</script>", "");
        return b.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*\"[^\"]*\"", "");
    }
}
