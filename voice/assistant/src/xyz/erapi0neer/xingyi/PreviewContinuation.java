package xyz.erapi0neer.xingyi;

/** In-memory binding of one spoken confirmation to the preview visible when it was recognized. */
final class PreviewContinuation {
    enum Status { CONFIRMED, WAITING, RUNNING, NO_PREVIEW, STALE }
    static final class Bound {
        final Runnable confirm, describe;
        final long shownAt, expires;
        Bound(Runnable confirm, Runnable describe, long shownAt, long expires) {
            this.confirm=confirm; this.describe=describe; this.shownAt=shownAt; this.expires=expires;
        }
    }
    private Bound pending;
    private boolean running;
    private long visibleSince=-1, hiddenAt=-1;

    synchronized void visible(boolean visible,long now) {
        if(visible){visibleSince=now;hiddenAt=-1;}
        else if(hiddenAt<0)hiddenAt=now;
    }
    synchronized void reset(boolean busy) { running=busy; pending=null; }
    synchronized void offer(Runnable confirm,Runnable describe,long now,long lifetime) {
        pending=new Bound(confirm,describe,now,now+lifetime);
        running=false;
    }
    synchronized boolean visibleWhen(long spoken,long now) {
        return visibleSince>=0 && spoken>=visibleSince && (hiddenAt<0 || spoken<=hiddenAt && now-hiddenAt<=2400)
                && spoken<=now && now-spoken<=12000;
    }
    synchronized Bound bind(long spoken,long now) {
        return visibleWhen(spoken,now) && pending!=null && spoken>=pending.shownAt && now<pending.expires ? pending : null;
    }
    synchronized Status decide(Bound expected,long spoken,long now,boolean explicitApproval) {
        if(expected!=null && expected==bind(spoken,now)) {
            if(!explicitApproval) return Status.WAITING;
            pending=null;
            running=true;
            return Status.CONFIRMED;
        }
        if(pending!=null || expected!=null) return Status.STALE;
        return running ? Status.RUNNING : Status.NO_PREVIEW;
    }
}
