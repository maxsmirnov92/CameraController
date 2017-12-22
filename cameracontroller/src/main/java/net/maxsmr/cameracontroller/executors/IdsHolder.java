package net.maxsmr.cameracontroller.executors;

import java.util.concurrent.atomic.AtomicInteger;

// TODO move to taskutils
public class IdsHolder {

    private final AtomicInteger lastId ;

    private final int initialValue;

    public IdsHolder(int initialValue) {
        lastId = new AtomicInteger(this.initialValue = initialValue);
    }

    public final int get() {
        return lastId.get();
    }

    public int incrementAndGet() {
        return lastId.incrementAndGet();
    }

    public void reset() {
        lastId.set(initialValue);
    }
}
