import opentrap.testkit.Harness;

/**
 * Entry point for the test run.
 *
 * <p>Suites are listed explicitly so the run order is fixed and a missing suite
 * is a compile error rather than a silently skipped file.
 */
public final class RunTests {

    public static void main(String[] args) {
        // Preferences reads this once, when the class initialises, so it must come first.
        System.setProperty("java.util.prefs.PreferencesFactory", "opentrap.testkit.MemoryPreferencesFactory");
        if (!(java.util.prefs.Preferences.userRoot() instanceof opentrap.testkit.MemoryPreferences)) {
            System.err.println("refusing to run: the tests would use the real preferences store");
            System.exit(2);
        }
        System.out.println("OpenTRAP tests");
        System.out.println();
        // The JDK loads the system font list on the first font lookup of the
        // process, about 0.6 s on macOS. Paid inside a case, it is billed to
        // whichever case measures text first and makes it look slow.
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();

        new opentrap.testkit.MemoryPreferencesTest().run();
        new opentrap.core.ParameterParsingTest().run();
        new opentrap.core.HttpModeTest().run();
        new opentrap.core.TlsGroupsTest().run();
        new opentrap.core.UpstreamProxyTest().run();
        new opentrap.core.HttpClientGuardTest().run();
        new opentrap.core.HttpClientRedirectTest().run();
        new opentrap.core.ProjectSettingsTest().run();
        new opentrap.core.ScopeDisplayTest().run();
        new opentrap.core.ScopePrefixTest().run();
        new opentrap.core.ScopeUrlRegexTest().run();
        new opentrap.core.ScopePathTest().run();
        new opentrap.core.MessageStoreCapTest().run();
        new opentrap.core.ProjectStoreTest().run();
        new opentrap.core.ProjectSerialTest().run();
        new opentrap.core.MessageStoreDiskTest().run();
        new opentrap.core.WebSocketStoreTest().run();
        new opentrap.session.SessionTest().run();
        new opentrap.intruder.PayloadProcessorTest().run();
        new opentrap.report.ExportTest().run();
        new opentrap.discovery.DiscoveryTest().run();
        new opentrap.decoder.DecoderTransformsTest().run();
        new opentrap.decoder.DecoderFocusTest().run();
        new opentrap.ui.MessageEditorJsonTest().run();
        new opentrap.core.HttpMessageTitleTest().run();
        new opentrap.csrf.CsrfPocTest().run();
        new opentrap.sequencer.SequencerStatsTest().run();
        new opentrap.sequencer.CaptureRunTest().run();
        new opentrap.organizer.OrganizerTest().run();
        new opentrap.engine.EngineMappingTest().run();
        new opentrap.proxy.ProxyDefaultsTest().run();
        new opentrap.proxy.ConnectionRunnerTest().run();
        new opentrap.proxy.WebSocketFrameTest().run();
        new opentrap.proxy.WsMatchReplaceTest().run();
        new opentrap.proxy.HistoryFilterTest().run();
        new opentrap.proxy.HtmlRewriteTest().run();
        new opentrap.proxy.LoopGuardTest().run();
        new opentrap.proxy.EmbeddedBrowserLookupTest().run();
        new opentrap.proxy.ProxyLoopTest().run();
        new opentrap.engine.ScanConfigStoreTest().run();
        new opentrap.engine.EngineShutdownTest().run();
        new opentrap.ui.SendToComparerTest().run();
        new opentrap.ui.CurlCommandTest().run();
        new opentrap.ui.FontsTest().run();
        new opentrap.ui.ThemePaletteTest().run();
        new opentrap.ui.AppIconTest().run();
        new opentrap.ui.WordmarkTest().run();
        new opentrap.ui.MacMenuBarTest().run();
        new opentrap.ui.MacKeysTest().run();
        new opentrap.ui.HotkeysTest().run();
        new opentrap.ui.UiScaleTest().run();
        new opentrap.ui.TabbedPaneListenerTest().run();
        new opentrap.ui.IssueBadgeTest().run();
        new opentrap.ui.FieldUniformityTest().run();
        new opentrap.ui.SettingsPagesTest().run();
        new opentrap.ui.IssueBurstTest().run();
        new opentrap.dashboard.ScanWizardTest().run();
        new opentrap.core.MessageCharsetTest().run();
        new opentrap.core.IssueAttributionTest().run();
        new opentrap.collaborator.CollaboratorPayloadTest().run();
        new opentrap.proxy.CertificateModeTest().run();
        new opentrap.proxy.CertificateAuthorityIsolationTest().run();
        new opentrap.core.OwnerOnlyTest().run();
        new opentrap.extensions.store.VersionTest().run();
        new opentrap.extensions.store.ZapFeedTest().run();
        new opentrap.extensions.store.ZapAddOnFileTest().run();
        new opentrap.extensions.store.StoreRecordTest().run();
        new opentrap.extensions.store.PendingApplyTest().run();
        new opentrap.ui.ProjectTasksTest().run();
        new opentrap.ui.ProjectSlimTest().run();
        new opentrap.ui.ProjectFileTest().run();
        new opentrap.ui.ProjectFilterTest().run();
        new opentrap.ui.ProjectSettingsFileTest().run();
        new opentrap.ui.ProjectTrustTest().run();
        new opentrap.ui.ProjectCopyScopeTest().run();
        new opentrap.repeater.RepeaterSaveStateTest().run();
        new opentrap.repeater.RepeaterRedirectModeTest().run();
        new opentrap.ui.ScopeEditorTest().run();
        new opentrap.ui.HtmlGuardTest().run();
        new opentrap.ui.HtmlSitesTest().run();
        new opentrap.dashboard.PhaseHtmlTest().run();
        new opentrap.dashboard.DashboardContextTest().run();
        new opentrap.ui.QuitRequestTest().run();

        // Last: it installs the full theme, which changes UI defaults for the
        // rest of the JVM.
        new opentrap.ui.ThemeHtmlTest().run();

        System.exit(Harness.report());
    }
}
