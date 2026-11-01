package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class OpenPolicyTest {
    @Test fun askMeansAskEveryTime() {
        assertNull(OpenPolicy.decide(OpenMode.ASK, true)); assertNull(OpenPolicy.decide(OpenMode.ASK, false))
    }

    @Test fun builtInViewerOnlyForImages_othersFallBackToDefault() {
        assertEquals(OpenAction.INTERNAL, OpenPolicy.decide(OpenMode.INTERNAL, true))
        assertEquals(OpenAction.DEFAULT, OpenPolicy.decide(OpenMode.INTERNAL, false))
    }

    @Test fun chooserAndDefaultAreHonouredForEverything() {
        for (img in listOf(true, false)) {
            assertEquals(OpenAction.CHOOSER, OpenPolicy.decide(OpenMode.CHOOSER, img))
            assertEquals(OpenAction.DEFAULT, OpenPolicy.decide(OpenMode.DEFAULT, img))
        }
    }

    @Test fun imageNames() {
        assertTrue(OpenPolicy.isImageName("a.JPG")); assertTrue(OpenPolicy.isImageName("x.y.png")); assertTrue(OpenPolicy.isImageName("p.HEIC"))
        assertFalse(OpenPolicy.isImageName("movie.mp4")); assertFalse(OpenPolicy.isImageName("noext"))
    }

    @Test fun rememberMapping_neverRemembersTheExplorer() {
        assertEquals(OpenMode.INTERNAL, OpenPolicy.modeToRemember(OpenAction.INTERNAL))
        assertEquals(OpenMode.CHOOSER, OpenPolicy.modeToRemember(OpenAction.CHOOSER))
        assertEquals(OpenMode.DEFAULT, OpenPolicy.modeToRemember(OpenAction.DEFAULT))
        assertNull(OpenPolicy.modeToRemember(OpenAction.EXPLORER))
    }

    @Test fun unknownSavedModeFallsBackToAsk() {
        assertEquals(OpenMode.ASK, OpenMode.fromName(null)); assertEquals(OpenMode.ASK, OpenMode.fromName("nope"))
        assertEquals(OpenMode.CHOOSER, OpenMode.fromName("CHOOSER"))
    }
}
