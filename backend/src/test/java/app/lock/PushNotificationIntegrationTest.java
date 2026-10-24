package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.notification.LogPushSender;
import app.lock.notification.NotificationJobs;
import app.lock.notification.NotificationService;
import app.lock.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 2.7: push notifications through the outbox, with preferences and scheduled reminders. */
class PushNotificationIntegrationTest extends IntegrationTest {

    @Autowired
    LogPushSender push;

    @Autowired
    NotificationService notifications;

    @Autowired
    NotificationJobs jobs;

    @BeforeEach
    void clearPush() {
        push.clear();
    }

    @Test
    void lockingNotifiesTheCreatorWithoutContent() throws Exception {
        TestUser ada = register("Ada");
        String token = device(ada);
        String memory = createMemory(ada, personalVault(ada), "Secret title");
        lockNow(ada, memory);
        sendAll();

        List<LogPushSender.Message> sent = sentTo(token);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().title()).isEqualTo("Your memory is locked");
        assertThat(sent.getFirst().data()).containsEntry("memoryId", memory).containsEntry("category", "MEMORY_LOCKED");
        // Content is encrypted at rest and never goes to a push provider.
        assertThat(sent.getFirst().title() + sent.getFirst().body()).doesNotContain("Secret title");
    }

    @Test
    void preferencesTurnCategoriesOff_butNotDeletionReminders() throws Exception {
        TestUser bo = register("Bo");
        String token = device(bo);
        mvc.perform(json(patch("/notification-preferences"), """
                        {"MEMORY_LOCKED": false}""").with(bo.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MEMORY_LOCKED").value(false))
                .andExpect(jsonPath("$.ON_THIS_DAY").value(true));
        mvc.perform(json(patch("/notification-preferences"), """
                        {"DELETION_REMINDER": false}""").with(bo.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_OPTIONAL"));

        lockNow(bo, createMemory(bo, personalVault(bo), "Quiet"));
        sendAll();
        assertThat(sentTo(token)).isEmpty();
    }

    @Test
    void accountDeletionRemindersAt7And1Days_cancelledWithTheRequest() throws Exception {
        TestUser cy = register("Cy");
        String token = device(cy);
        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(cy.password())).with(cy.auth()))
                .andExpect(status().isAccepted());
        sendAll();
        assertThat(sentTo(token)).isEmpty(); // scheduled, not sent yet

        clock.advance(Duration.ofDays(23).plusMinutes(1));
        sendAll();
        assertThat(sentTo(token)).extracting(LogPushSender.Message::body).containsExactly("In 7 days. You can still cancel it in Lock.");

        mvc.perform(delete("/account/deletion-request").with(cy.auth())).andExpect(status().isOk());
        drainOutbox();
        clock.advance(Duration.ofDays(6));
        sendAll();
        assertThat(sentTo(token)).hasSize(1); // the 1-day reminder was cancelled
    }

    @Test
    void memoryDeletionRemindersAndRejectedUploadsAndInvitations() throws Exception {
        TestUser di = register("Di");
        TestUser ed = register("Ed");
        String diToken = device(di);
        String edToken = device(ed);

        // Invitation
        String vault = read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Trip"}""").with(di.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/vaults/" + vault + "/members"), """
                        {"email":"%s"}""".formatted(ed.email())).with(di.auth()))
                .andExpect(status().isCreated());
        sendAll();
        assertThat(sentTo(edToken)).extracting(LogPushSender.Message::title).containsExactly("You're invited to a shared vault");

        // Rejected upload
        String draft = createMemory(di, personalVault(di), "With a bad file");
        upload(di, draft, "image/jpeg", MediaProcessingIntegrationTest.EICAR);
        sendAll();
        assertThat(sentTo(diToken)).extracting(LogPushSender.Message::title).containsExactly("A file couldn't be added");

        // Memory deletion: two reminders, sent at 23 and 29 days.
        String memory = createMemory(di, personalVault(di), "To delete");
        lockNow(di, memory);
        mvc.perform(json(post("/memories/" + memory + "/deletion-request"), """
                        {"password":"%s"}""".formatted(di.password())).with(di.auth()))
                .andExpect(status().isAccepted());
        sendAll();
        int before = sentTo(diToken).size();
        clock.advance(Duration.ofDays(30));
        sendAll();
        assertThat(sentTo(diToken).subList(before, sentTo(diToken).size()))
                .extracting(LogPushSender.Message::title).containsExactly("A memory will be deleted", "A memory will be deleted");
        // Leave no pending deletion behind for other tests' deletion jobs.
        mvc.perform(delete("/memories/" + memory + "/deletion-request").with(di.auth())).andExpect(status().isOk());
    }

    @Test
    void capsuleRevealGoesToEveryMemberWhenItOpens() throws Exception {
        TestUser fay = register("Fay");
        TestUser gil = register("Gil");
        String vault = sharedVault(fay, gil);
        String gilToken = device(gil);
        Instant reveal = START.plus(Duration.ofDays(10));
        String capsule = read(mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"title":"Open later","revealAt":"%s"}""".formatted(reveal)).with(fay.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        lockNow(fay, capsule);
        sendAll();
        assertThat(capsuleNotices(gilToken)).isEmpty();

        clock.set(reveal.plusSeconds(1));
        sendAll();
        assertThat(capsuleNotices(gilToken)).hasSize(1);
    }

    @Test
    void onThisDayIsQueuedOncePerDay() throws Exception {
        TestUser hal = register("Hal");
        String token = device(hal);
        String vault = personalVault(hal);
        String memory = read(mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"title":"Last year","occurredAt":"%s"}""".formatted(START.minus(Duration.ofDays(365)))).with(hal.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        lockNow(hal, memory);
        sendAll();
        push.clear();

        clock.set(START.plus(Duration.ofDays(366)).minus(Duration.ofDays(1))); // same calendar day, a year on
        jobs.queueOnThisDay();
        jobs.queueOnThisDay();
        sendAll();
        assertThat(sentTo(token)).extracting(LogPushSender.Message::title).containsExactly("On this day");
    }

    @Test
    void devicesMoveBetweenAccountsAndCanBeRemoved() throws Exception {
        TestUser ian = register("Ian");
        TestUser jo = register("Jo");
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        register(ian, token);
        register(jo, token); // the same phone, now signed in as Jo
        mvc.perform(get("/devices").with(ian.auth())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/devices").with(jo.auth())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(json(delete("/devices"), """
                        {"pushToken":"%s"}""".formatted(token)).with(jo.auth()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/devices").with(jo.auth())).andExpect(jsonPath("$.length()").value(0));
    }

    private String device(TestUser user) throws Exception {
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        register(user, token);
        return token;
    }

    private void register(TestUser user, String token) throws Exception {
        mvc.perform(json(post("/devices"), """
                        {"pushToken":"%s","platform":"IOS"}""".formatted(token)).with(user.auth()))
                .andExpect(status().isOk());
    }

    private void sendAll() {
        drainOutbox();
        while (notifications.sendDue(100) > 0) {
            // drain
        }
    }

    private List<LogPushSender.Message> capsuleNotices(String token) {
        return sentTo(token).stream().filter(m -> "CAPSULE_REVEALED".equals(m.data().get("category"))).toList();
    }

    private List<LogPushSender.Message> sentTo(String token) {
        return push.sent().stream().filter(m -> m.token().equals(token)).toList();
    }
}
