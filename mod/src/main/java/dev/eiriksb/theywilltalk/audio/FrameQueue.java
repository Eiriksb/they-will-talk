package dev.eiriksb.theywilltalk.audio;

import java.util.ArrayDeque;

/**
 * Audio pushed in pieces of any length (as a streaming TTS hands them over), taken out in Simple Voice Chat's 20 ms
 * frames. What doesn't fill a frame waits for the next piece instead of being padded with silence: padding each piece's
 * last frame put a tiny gap into the voice several times a second, which sounded crackly. Only the very end (after
 * {@link #finish}) is padded. Playback starts once {@link #BUFFER} frames are queued (or it's finished), and if it runs
 * dry it waits for that many again, so a renderer that's briefly slow makes one short pause rather than a stutter.
 * Thread-safe: one thread pushes, Simple Voice Chat's takes.
 */
public final class FrameQueue {
    /** frames queued before playing (100 ms) */
    public static final int BUFFER = 5;

    private final ArrayDeque<short[]> frames = new ArrayDeque<>();
    private final short[] carry = new short[AudioDsp.FRAME];
    private int carried;
    private boolean finished;
    private boolean playing;

    public synchronized void push(short[] pcm) {
        int i = 0;
        while (i < pcm.length) {
            int n = Math.min(AudioDsp.FRAME - carried, pcm.length - i);
            System.arraycopy(pcm, i, carry, carried, n);
            carried += n;
            i += n;
            if (carried == AudioDsp.FRAME) {
                frames.add(carry.clone());
                carried = 0;
            }
        }
    }

    /** Nothing more is coming: what's left is padded out to a last frame. */
    public synchronized void finish() {
        if (carried > 0) {
            short[] last = new short[AudioDsp.FRAME];
            System.arraycopy(carry, 0, last, 0, carried);
            frames.add(last);
            carried = 0;
        }
        finished = true;
    }

    /** The next frame to play, or null while buffering (play silence) or when it's all played (see {@link #ended}). */
    public synchronized short[] poll() {
        if (!playing && (frames.size() >= BUFFER || finished && !frames.isEmpty())) {
            playing = true;
        }
        if (!playing) {
            return null;
        }
        short[] f = frames.poll();
        if (f == null) {
            playing = false; // ran dry: buffer up again
        }
        return f;
    }

    /** Whether it's finished and all played. */
    public synchronized boolean ended() {
        return finished && frames.isEmpty();
    }

    public synchronized boolean finished() {
        return finished;
    }

    public synchronized void clear() {
        frames.clear();
        carried = 0;
    }

    /** Frames queued, counting a part-filled one. */
    public synchronized int queued() {
        return frames.size() + (carried > 0 ? 1 : 0);
    }
}
