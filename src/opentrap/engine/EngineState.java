package opentrap.engine;

/** Where the scan engine is in its life, as {@link ZapEngine#state()} reports it. */
public enum EngineState { NOT_STARTED, STARTING, RUNNING, STOPPED, FAILED }
