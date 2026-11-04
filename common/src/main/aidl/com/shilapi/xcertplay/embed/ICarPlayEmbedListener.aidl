package com.shilapi.xcertplay.embed;

/** Callbacks from xcertplay to an embedding app. All one-way. */
oneway interface ICarPlayEmbedListener {
    /**
     * [state] is one of: 0 idle (no session), 1 needs setup (open xcertplay once to grant
     * permissions), 2 connecting, 3 streaming (the main stream is drawing), 4 stopped after
     * failed retries, 5 paused (xcertplay's settings are open). [stage] says it in words.
     */
    void onStateChanged(int state, String stage);

    /** CarPlay's car-maker tile was pressed. The client decides what it means (for example, leave CarPlay). */
    void onHostUiRequested();
}
