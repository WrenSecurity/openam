/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */
package com.sun.identity.plugin.session.impl;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.AssertJUnit.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.forgerock.openam.cts.continuous.watching.ContinuousListener;
import org.forgerock.openam.cts.continuous.watching.SessionWatchingNotSupported;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.service.SessionService;
import com.iplanet.dpro.session.watchers.listeners.SessionDeletionListener;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenID;
import com.sun.identity.plugin.session.SessionException;
import com.sun.identity.plugin.session.SessionListener;

public class FMSessionNotificationTest {
    @Mock
    private SessionService mockSessionService;
    @Mock
    private ScheduledExecutorService mockScheduledService;

    private ArgumentCaptor<SessionDeletionListener> listenerCaptor;
    private FMSessionNotification sessionNotification;
    private SessionListener callerSessionListener;

    @BeforeMethod
    public void setup() throws SessionWatchingNotSupported {
        MockitoAnnotations.initMocks(this);
        listenerCaptor = ArgumentCaptor.forClass(SessionDeletionListener.class);
        doNothing().when(mockSessionService).registerListener(listenerCaptor.capture());
        callerSessionListener = mock(SessionListener.class);

        sessionNotification = new FMSessionNotification(mockScheduledService, mockSessionService);
    }

    /* Argument Processing */

    @Test (expectedExceptions = NullPointerException.class)
    public void shouldNotAllowNullTokenId() throws SessionException {
        sessionNotification.store(null, mock(SessionListener.class));
    }

    @Test (expectedExceptions = NullPointerException.class)
    public void shouldNotAllowNullListenerInstance() throws SessionException {
        sessionNotification.store(mock(SSOToken.class), null);
    }

    /* Initialisation */

    @Test
    public void shouldRegisterwithSessionServiceOnCreationForNotificationsOnSession() throws SessionWatchingNotSupported {
        verify(mockSessionService).registerListener(any(ContinuousListener.class));
    }

    /* Notification */

    @Test
    public void shouldRespondToContinuousQueryNotifications() throws SessionException {
        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);
        sessionNotification.store(token, callerSessionListener);
        notifySessionDeleted(tokenId);
        verify(callerSessionListener).sessionInvalidated(anyObject());
    }

    @Test
    public void shouldIgnoreNotificationsForNonRegisteredTokenID() {
        notifySessionDeleted("not-registered");
    }

    @Test
    public void shouldRespondToNotificationOnlyOnce() throws SessionException {
        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);
        sessionNotification.store(token, callerSessionListener);
        notifySessionDeleted(tokenId);
        notifySessionDeleted(tokenId);
        verify(callerSessionListener, times(1)).sessionInvalidated(anyObject());
    }

    @Test
    public void shouldNotifyAllSessionListenersOnNotification() throws SessionException {
        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);

        SessionListener first = mock(SessionListener.class);
        SessionListener second = mock(SessionListener.class);

        sessionNotification.store(token, first);
        sessionNotification.store(token, second);

        notifySessionDeleted(tokenId);

        verify(first).sessionInvalidated(anyObject());
        verify(second).sessionInvalidated(anyObject());
    }

    /* Session Timeout */

    @Test
    public void shouldTimeoutSessionAtMaxSessionTimeout() throws SSOException, SessionException {
        setupInstantExecutingScheduledService();

        SSOToken mockToken = mockSessionObject("badger");
        given(mockToken.getMaxSessionTime()).willReturn(0L);
        sessionNotification.store(mockToken, callerSessionListener);
        verify(callerSessionListener).sessionInvalidated(anyObject());
    }

    @Test
    public void shouldNotifyAllSessionListenersOnMaxSessionTimeout() throws SSOException, SessionException {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(mockScheduledService.schedule(runnableCaptor.capture(), anyLong(), any(TimeUnit.class)))
                .willReturn(mock(ScheduledFuture.class))
                .willThrow(new IllegalStateException("Should not be called"));

        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);
        given(token.getMaxSessionTime()).willReturn(0L);

        SessionListener first = mock(SessionListener.class);
        SessionListener second = mock(SessionListener.class);

        sessionNotification.store(token, first);
        sessionNotification.store(token, second);

        runnableCaptor.getValue().run();

        verify(first).sessionInvalidated(anyObject());
        verify(second).sessionInvalidated(anyObject());
    }

    @Test
    public void shouldOnlyNotifyEachListenerOnlyOnce() throws SSOException, SessionException {
        setupInstantExecutingScheduledService();

        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);
        given(token.getMaxSessionTime()).willReturn(0L);

        SessionListener first = mock(SessionListener.class);
        sessionNotification.store(token, first);

        SessionListener second = mock(SessionListener.class);
        sessionNotification.store(token, second);

        verify(first, times(1)).sessionInvalidated(anyObject());
        verify(second, times(1)).sessionInvalidated(anyObject());
    }

    @Test
    public void shouldHandleNegativeMaxSessionTimeout() throws SSOException, SessionException {
        setupInstantExecutingScheduledService();

        String tokenId = "badger";
        SSOToken token = mockSessionObject(tokenId);
        given(token.getMaxSessionTime()).willReturn(-1L);

        sessionNotification.store(token, callerSessionListener);

        verify(mockScheduledService).schedule(any(Runnable.class), eq(0L), any(TimeUnit.class));
    }

    @Test (expectedExceptions = SessionException.class)
    public void shouldFailToRegisterListenerIfMaxSessionTimeNotAvailable() throws SSOException, SessionException {
        SSOToken ssoToken = mockSessionObject("exploding badger");
        SSOException error = new SSOException("test");
        given(ssoToken.getMaxSessionTime()).willThrow(error);

        sessionNotification.store(ssoToken, mock(SessionListener.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldContinueToOperateWhenAddSessionListenerFails() throws SessionWatchingNotSupported, com.iplanet.dpro.session.SessionException, SessionException {
        // Given

        // Capture the thread so we can control it afterwards
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(mockScheduledService.schedule(runnableCaptor.capture(), anyLong(), any(TimeUnit.class))).willReturn(mock(ScheduledFuture.class));

        // Set the SessionService to explode on call
        SessionWatchingNotSupported error = new SessionWatchingNotSupported("");
        doThrow(error).when(mockSessionService).notifyListenerFor(any(SessionID.class), any(ContinuousListener.class));

        SSOToken token = mockSessionObject("badger");
        SessionListener mockListener = mock(SessionListener.class);
        sessionNotification.store(token, mockListener);

        // When
        runnableCaptor.getValue().run();

        // Then
        verify(mockListener).sessionInvalidated(anyObject());
    }

    /* Concurrency */

    /**
     * In this scenario we consider whether it is possible for the session to both timeout
     * and to receive a notification from the continuous listener at the same time, therefore
     * resulting in the associated listeners firing twice.
     */
    @Test
    public void shouldAvoidDoubleNotifications() throws InterruptedException, SessionException {
        int SCALE = 1000;

        // Generate token to listener mapping
        final Map<String, SessionListener> keys = new HashMap<>();
        for (int ii = 0; ii < SCALE; ii++) {
            keys.put(UUID.randomUUID().toString(), mock(SessionListener.class));
        }

        // Set ScheduledExecutorService to provide up timeout runnable
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(mockScheduledService.schedule(runnableCaptor.capture(), anyLong(), any(TimeUnit.class))).willReturn(mock(ScheduledFuture.class));

        // Generate list of notification runnables
        final List<Runnable> notifications = new ArrayList<>();
        for (final String key : keys.keySet()) {
            // Notification
            notifications.add(new Runnable() {
                @Override
                public void run() {
                    notifySessionDeleted(key);
                }
            });
        }

        // Register all tokens
        for (String key : keys.keySet()) {
            SSOToken token = mockSessionObject(key);
            sessionNotification.store(token, keys.get(key));
        }

        // Acquire the scheduled runnables from the ScheduledExecutorService
        List<Runnable> timeouts = new ArrayList<>(runnableCaptor.getAllValues());

        assertThat(timeouts.size()).isEqualTo(notifications.size());

        final ExecutorService service = Executors.newFixedThreadPool(SCALE);
        try {
            // Interleave the runnables to create the possible collision conditions
            Random random = new Random();
            for (int ii = 0; ii < timeouts.size(); ii++) {
                // Ensure order is not predictable
                if (random.nextBoolean()) {
                    service.submit(notifications.get(ii));
                    service.submit(timeouts.get(ii));
                } else {
                    service.submit(timeouts.get(ii));
                    service.submit(notifications.get(ii));
                }
            }
        } finally {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
        }

        // Finally verify all listeners where only invoked once.
        for (SessionListener mockListener : keys.values()) {
            verify(mockListener, times(1)).sessionInvalidated(anyObject());
        }
    }

    /**
     * In this scenario, we consider the impact of attempting to register multiple
     * session listeners against the same sso token id. We are attempting to loose
     * an update due to the lack of atomic CAS operation around registration.
     */
    @Test
    public void shouldEnsureConsistencyDuringRegistration() throws SessionException, InterruptedException {

        // Setup lots of keys
        final Map<String, AtomicInteger> keys = new ConcurrentHashMap<>();
        for (int ii = 0; ii < 1000; ii++) {
            keys.put(UUID.randomUUID().toString(), new AtomicInteger(0));
        }
        System.out.println("Generated Keys");

        /**
         * If there are many requests for the same session being requested
         * via {@link FMSessionNotification#store(SSOToken, SessionListener)}
         * it is possible to loose updates.
         */
        final ExecutorService service = Executors.newFixedThreadPool(100);
        int LISTENER_COUNT = 3;
        try {
            for (final String key : keys.keySet()) {
                for (int ii = 0; ii < LISTENER_COUNT; ii++) {
                    service.submit(new Runnable() {
                        @Override
                        public void run() {
                            SSOToken token = mockSessionObject(key);
                            final AtomicInteger counter = keys.get(key);
                            try {
                                sessionNotification.store(token, new SessionListener() {
                                    @Override
                                    public void sessionInvalidated(Object session) {
                                        counter.incrementAndGet();
                                    }
                                });
                            } catch (SessionException e) {
                                fail();
                            }
                        }
                    });
                }
            }
            System.out.println("Setup " + LISTENER_COUNT + " listeners per key");
        } finally {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
            System.out.println("Temporary executor service shutdown");
        }

        // Notify on all keys
        for (String key : keys.keySet()) {
            notifySessionDeleted(key);
        }
        System.out.println("Notified all keys of deletion");

        // Verify each counter had correct amount of invocations.
        for (String key : keys.keySet()) {
            assertThat(keys.get(key).get()).isEqualTo(LISTENER_COUNT);
        }
        System.out.println("Verified all deletions");
    }

    /**
     * In this scenario we will be registering and notifying on the same session. If there is any
     * inconsistency between these two operations there should be a NPE generated.
     */
    @Test
    public void shouldBeConsistentDuringRegistrationAndNotification() throws SessionException, InterruptedException {
        // Setup lots of keys
        final String key = UUID.randomUUID().toString();
        final SSOToken token = mockSessionObject(key);

        final int counter = 10;
        final ExecutorService service = Executors.newFixedThreadPool(counter);
        try {
            for (int threads = 0; threads < counter; threads++) {
                service.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (int attempts = 0; attempts < 10000; attempts++) {
                            try {
                                sessionNotification.store(token, mock(SessionListener.class));
                            } catch (SessionException e) {
                                fail(e.getMessage());
                            }
                            notifySessionDeleted(key);
                        }
                    }
                });
            }
        } finally {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Configure the {@link ScheduledExecutorService} to execute the
     * runnable provided instantly, and on the same thread.
     */
    private void setupInstantExecutingScheduledService() {
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                runnableCaptor.getValue().run();
                return null;
            }
        }).when(mockScheduledService).schedule(runnableCaptor.capture(), anyLong(), any(TimeUnit.class));
    }

    /**
     * Notifies the {@link SessionDeletionListener} which was registered with
     * the {@link FMSessionNotification} that the session has now been deleted.
     * @param tokenId Non null.
     */
    private void notifySessionDeleted(String tokenId) {
        SessionDeletionListener listener = listenerCaptor.getValue();
        listener.sessionDeleted(tokenId);
    }

    private static SSOToken mockSessionObject(String key) {
        SSOToken mockToken = mock(SSOToken.class);
        SSOTokenID mockTokenID = mock(SSOTokenID.class);
        given(mockToken.getTokenID()).willReturn(mockTokenID);
        given(mockTokenID.toString()).willReturn(key);
        return mockToken;
    }
}
