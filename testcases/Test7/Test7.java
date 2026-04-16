// Test7: k=1 object sensitivity — container pattern.
//
// This is the canonical k-obj test case.
// PTA of Container7.run() sees pts(this) = UNKNOWN → cannot narrow.
// k-obj: at the call site box.run(), the CALLER's PTA state says:
//   pts(box) = {AllocSite<Container7> A}
//   callerState[A.worker] = {AllocSite<HardWorker7>}
// So run() → this.worker.doWork() → HardWorker7.doWork → MONO.
//
// Expected:
//   After CHA  : BIMORPHIC or POLY (Worker7 has 2 subtypes)
//   After PTA  : BIMORPHIC (receiver=this.worker, PTA sees UNKNOWN for this)
//   After k-Obj: MONO (callerState[box.worker]={HardWorker7})
//   Rewrite    : INLINE (HardWorker7.doWork is small)

interface Worker7 {
    void doWork();
}

class HardWorker7 implements Worker7 {
    @Override
    public void doWork() { System.out.println("Working hard"); }
}

class LazyWorker7 implements Worker7 {
    @Override
    public void doWork() { System.out.println("Working lazily"); }
}

class Container7 {
    Worker7 worker;

    Container7(Worker7 w) {
        this.worker = w;
    }

    void run() {
        worker.doWork();   // PTA: pts(this)=UNKNOWN → pts(this.worker)=UNKNOWN
                           // k-obj: caller knows worker={HardWorker7} → MONO
    }
}

public class Test7 {
    public static void main(String[] args) {
        Worker7 w = new HardWorker7();            // AllocSite<HardWorker7>
        Container7 box = new Container7(w);        // AllocSite<Container7>
        // callerState[box.worker] = {AllocSite<HardWorker7>}
        box.run();    // k-obj: look up box.worker in caller → HardWorker7 → MONO
    }
}