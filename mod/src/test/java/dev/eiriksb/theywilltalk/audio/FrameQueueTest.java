package dev.eiriksb.theywilltalk.audio;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameQueueTest {
    /** Pieces of any size come out as one unbroken sound: no silence between them (that sounded crackly). */
    @Test
    void piecesJoinWithoutGaps() {
        short[] voice = new short[48_000];
        for (int i = 0; i < voice.length; i++) {
            voice[i] = (short) (1 + (i % 1000)); // never zero, so a gap would show
        }
        FrameQueue q = new FrameQueue();
        Random r = new Random(1);
        for (int i = 0; i < voice.length; ) {
            int n = Math.min(voice.length - i, 1 + r.nextInt(3000)); // like a socket hands it over
            short[] piece = new short[n];
            System.arraycopy(voice, i, piece, 0, n);
            q.push(piece);
            i += n;
        }
        q.finish();
        short[] out = new short[voice.length];
        int at = 0;
        short[] f;
        while ((f = q.poll()) != null) {
            System.arraycopy(f, 0, out, at, Math.min(f.length, out.length - at));
            at += f.length;
        }
        assertEquals(voice.length, at, "48000 samples are exactly 50 frames");
        assertArrayEquals(voice, out, "the same sound, nothing put in between");
        assertTrue(q.ended());
    }

    /** It waits for a little to be queued before it plays, and only the very end is padded. */
    @Test
    void buffersThenPadsOnlyTheEnd() {
        FrameQueue q = new FrameQueue();
        q.push(new short[AudioDsp.FRAME * 2 + 10]);
        assertNull(q.poll(), "two frames: still buffering");
        q.push(new short[AudioDsp.FRAME * 3]);
        assertTrue(q.poll() != null, "five frames: playing");
        q.finish();
        int frames = 1;
        while (q.poll() != null) {
            frames++;
        }
        assertEquals(6, frames, "five whole frames and the padded end");
    }
}
