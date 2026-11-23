package opentrap.core;

/**
 * What a project copy may keep. {@link #ALL} keeps everything; a filter built
 * from a scope keeps only data whose target the scope covers. Data with no known
 * target is never kept by a narrowed filter. Never serialised.
 */
public final class SaveFilter {

    /** Keeps everything. */
    public static final SaveFilter ALL = new SaveFilter(null);

    private final Scope scope;

    private SaveFilter(Scope scope) { this.scope = scope; }

    /** A filter that keeps what {@code scope} covers. */
    public static SaveFilter inScope(Scope scope) {
        return new SaveFilter(java.util.Objects.requireNonNull(scope));
    }

    /** True only for {@link #ALL}. */
    public boolean keepsAll() { return scope == null; }

    /** Whether data aimed at {@code svc} and {@code path} may be kept; an empty path counts as the root. */
    public boolean keeps(HttpService svc, String path) {
        if (scope == null) return true;
        if (svc == null) return false;
        return scope.isInScope(svc, path == null || path.isEmpty() ? "/" : path);
    }
}
