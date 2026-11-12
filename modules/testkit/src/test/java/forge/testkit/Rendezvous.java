package forge.testkit;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** When the gate turns it on, the two sample classes wait for each other: they can only both pass if they really run at the same time. */
final class Rendezvous {

    static volatile CyclicBarrier barrier;

    private Rendezvous() {}

    static void meet() throws Exception {
        CyclicBarrier b = barrier;
        if (b != null) {
            b.await(60, TimeUnit.SECONDS);
        }
    }
}
