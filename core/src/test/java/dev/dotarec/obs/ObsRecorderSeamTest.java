package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link ObsRecorder} seam is implementable by a simple in-memory fake -- which is the
 * whole reason the interface exists: the later FSM PR unit-tests against exactly this kind of fake
 * instead of a live OBS. If this fake stops compiling/passing, the seam has drifted in a way that
 * would break the FSM's testability.
 */
class ObsRecorderSeamTest {

    /** Minimal fake the FSM can drive deterministically. */
    static final class FakeObsRecorder implements ObsRecorder {
        boolean obsRunning = true;
        boolean sceneAndAudioOk = true;
        boolean connected;
        boolean recording;
        Instant confirmedAt;
        String savedPath = "C:\\videos\\fake.mkv";

        @Override
        public void connect() {
            connected = obsRunning;
        }

        @Override
        public boolean ensureConnected() {
            connect();
            return connected;
        }

        @Override
        public boolean isReady() {
            return connected && sceneAndAudioOk;
        }

        @Override
        public String startRecording() {
            if (!connected) {
                throw new ObsException("OBS is not connected");
            }
            confirmedAt = Instant.now(); // fake confirms synchronously
            recording = true;
            return confirmedAt.toString();
        }

        @Override
        public String stopRecording() {
            if (!connected) {
                throw new ObsException("OBS is not connected");
            }
            recording = false;
            return savedPath;
        }

        @Override
        public boolean isRecording() {
            return recording;
        }

        @Override
        public Instant recordConfirmedAt() {
            return confirmedAt;
        }
    }

    @Test
    void happyPath_connectArmRecordStop() {
        FakeObsRecorder obs = new FakeObsRecorder();

        assertThat(obs.ensureConnected()).isTrue();
        assertThat(obs.isReady()).isTrue();

        String startedAt = obs.startRecording();
        assertThat(startedAt).isNotNull();
        assertThat(obs.recordConfirmedAt()).isNotNull();
        assertThat(obs.isRecording()).isTrue();

        assertThat(obs.stopRecording()).isEqualTo("C:\\videos\\fake.mkv");
        assertThat(obs.isRecording()).isFalse();
    }

    @Test
    void downObs_ensureConnectedFalse_doesNotThrow() {
        FakeObsRecorder obs = new FakeObsRecorder();
        obs.obsRunning = false;

        assertThat(obs.ensureConnected()).isFalse();
        assertThat(obs.isReady()).isFalse();
    }

    @Test
    void recordingWhileDisconnected_throwsTypedObsException() {
        FakeObsRecorder obs = new FakeObsRecorder();
        obs.obsRunning = false;
        obs.ensureConnected();

        assertThatThrownBy(obs::startRecording).isInstanceOf(ObsException.class);
        assertThatThrownBy(obs::stopRecording).isInstanceOf(ObsException.class);
    }

    @Test
    void notReady_whenSceneOrAudioMissing_evenIfConnected() {
        FakeObsRecorder obs = new FakeObsRecorder();
        obs.sceneAndAudioOk = false;

        assertThat(obs.ensureConnected()).isTrue();
        assertThat(obs.isReady())
                .as("a green connection but no scene/audio must not be 'ready' to record")
                .isFalse();
    }
}
