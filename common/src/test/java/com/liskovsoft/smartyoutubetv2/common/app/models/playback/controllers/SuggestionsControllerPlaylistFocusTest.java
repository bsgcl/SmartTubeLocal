package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SuggestionsControllerPlaylistFocusTest {
    private static final class Calls implements InvocationHandler {
        final List<String> names = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            names.add(method.getName());
            args.add(arguments);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        }

        PlayerUI player() {
            return (PlayerUI) Proxy.newProxyInstance(
                    PlayerUI.class.getClassLoader(),
                    new Class<?>[]{PlayerUI.class}, this);
        }
    }

    @Test
    public void newLocalPlaylistVideoPreparesCarouselWithoutOpeningIt() {
        Calls events = new Calls();
        Video next = new Video();
        VideoGroup playlist = VideoGroup.from(Collections.singletonList(new Video()));

        SuggestionsController.updateLocalPlaylistOverlay(events.player(), playlist, next);

        assertEquals(Arrays.asList(
                "clearSuggestions", "updateSuggestions", "setNextTitle",
                "resetSuggestedPosition", "showControls"), events.names);
        assertSame(playlist, events.args.get(1)[0]);
        assertSame(next, events.args.get(2)[0]);
        assertEquals(Boolean.FALSE, events.args.get(4)[0]);
        assertFalse(events.names.contains("showSuggestions"));
    }

    @Test
    public void emptyPlaylistAlsoHidesOverlayAndResetsFocus() {
        Calls events = new Calls();

        SuggestionsController.updateLocalPlaylistOverlay(events.player(), null, null);

        assertEquals(Arrays.asList(
                "clearSuggestions", "resetSuggestedPosition", "showControls"), events.names);
        assertEquals(Boolean.FALSE, events.args.get(2)[0]);
    }
}
