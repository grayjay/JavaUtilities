package com.grayjay.javautils.upgradablelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import org.junit.rules.Timeout;

import com.grayjay.javautils.upgradablelock.UpgradableLock.Mode;

public class UpgradableLockTest {
  private static int MAX_TEST_LENGTH_MILLIS = 5_000;
  private static long MAX_WAIT_FOR_LOCK_MILLIS = 10;
  
  private UpgradableLock myLock;
  
  @Rule
  public Timeout myTimeout = new Timeout(MAX_TEST_LENGTH_MILLIS);
  
  @Before
  public void setup() {
    myLock = new UpgradableLock();
    Thread.interrupted();
  }

  @Test
  public void testWriteLock() throws Throwable {
    myLock.lock(Mode.WRITE);
    assertTrue(hasWriter());
    myLock.lock(Mode.WRITE);
    myLock.lock(Mode.READ);
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(isUnlocked());
  }

  @Test
  public void testUpgrading() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    assertTrue(hasUpgradable());
    myLock.lock(Mode.UPGRADABLE);
    assertTrue(hasUpgradable());
    myLock.lock(Mode.READ);
    assertTrue(hasUpgradable());
    myLock.upgrade();
    assertTrue(hasWriter());
    myLock.downgrade();
    assertTrue(hasUpgradable());
    myLock.lock(Mode.WRITE);
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(hasUpgradable());
    myLock.unlock();
    assertTrue(hasUpgradable());
    myLock.unlock();
    assertTrue(hasUpgradable());
    myLock.unlock();
    assertTrue(isUnlocked());
  }

  @Test
  public void testReadLock() throws Throwable {
    myLock.lock(Mode.READ);
    assertTrue(hasReaders());
    myLock.lock(Mode.READ);
    assertTrue(hasReaders());
    myLock.unlock();
    assertTrue(hasReaders());
    myLock.unlock();
    assertTrue(isUnlocked());
  }
  
  @Test
  public void clearUpgradesAfterFullUnlock() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    myLock.upgrade();
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(isUnlocked());
    myLock.lock(Mode.UPGRADABLE);
    assertTrue(hasUpgradable());
  }
  
  @Test
  public void keepUpgradesAfterReleasingWriteLock() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    myLock.lock(Mode.WRITE);
    myLock.upgrade();
    assertTrue(hasWriter());
    myLock.unlock();
    assertTrue(hasWriter());
    myLock.downgrade();
    assertTrue(hasUpgradable());
  }

  @Test
  public void blockUpgradeWithOtherReader() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    lockPermanently(Mode.READ);
    assertFalse(myLock.tryLock(Mode.WRITE));
  }

  @Test
  public void lockRecursively() throws Throwable {
    int mCount = 50;
    for (int i = 0; i < mCount; i++) {
      if (i % 3 == 1) myLock.lock(Mode.WRITE);
      else myLock.lock(Mode.UPGRADABLE);
    }
    for (int i = 0; i < mCount; i++) {
      assertTrue(hasUpgradable() || hasWriter());
      myLock.unlock();
    }
    assertTrue(isUnlocked());
  }

  @Test
  public void toStringWithNoHolds() {
    String mPattern = "UpgradableLock[%s, unlocked]";
    UpgradableLock mFair = new UpgradableLock(true);
    assertEquals(String.format(mPattern, "fair"), mFair.toString());
    UpgradableLock mNonFair = new UpgradableLock(false);
    assertEquals(String.format(mPattern, "non-fair"), mNonFair.toString());
  }
  
  @Test
  public void toStringWithWriteHold() {
    myLock.lock(Mode.WRITE);
    assertToStringThreadMessage("1 write/upgraded thread");
  }
  
  @Test
  public void toStringWithUpgradableHold() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    assertToStringThreadMessage("1 downgraded thread");
    myLock.upgradeInterruptibly();
    assertToStringThreadMessage("1 write/upgraded thread");
    myLock.downgrade();
    lockPermanently(Mode.READ);
    lockPermanently(Mode.READ);
    assertToStringThreadMessage("1 downgraded thread, 2 read threads");
  }
  
  @Test
  public void toStringWithReadHolds() throws Throwable {
    lockPermanently(Mode.READ);
    assertToStringThreadMessage("1 read thread");
    lockPermanently(Mode.READ);
    assertToStringThreadMessage("2 read threads");
  }
  
  private void assertToStringThreadMessage(String aExpected) {
    String mExpected = String.format("UpgradableLock[fair, %s]", aExpected);
    assertEquals(mExpected, myLock.toString());
  }

  @Test
  public void testTimeout() throws Throwable {
    lockPermanently(Mode.WRITE);
    for (Mode mMode : Mode.values()) {
      assertFalse(myLock.tryLock(mMode, 300, TimeUnit.NANOSECONDS));
    }
  }

  @Test
  public void testNegativeTimeout() throws Throwable {
    lockPermanently(Mode.UPGRADABLE);
    boolean mSuccess = myLock.tryLock(Mode.UPGRADABLE, -3L, TimeUnit.MICROSECONDS);
    assertFalse(mSuccess);
  }
  
  @Test
  public void testTryLockWhenAvailable() throws Throwable {
    for (Mode mMode : Mode.values()) {
      assertTrue(myLock.tryLock(mMode));
      myLock.unlock();
      assertTrue(isUnlocked());
    }
  }
  
  @Test
  public void testTryLockWhenNotAvailable() throws Throwable {
    lockPermanently(Mode.WRITE);
    for (Mode mMode : Mode.values()) {
      assertFalse(myLock.tryLock(mMode));
    }
  }

  @Test
  public void interruptDuringUpgrade() throws Throwable {
    interruptDuringLockOrUpgrade(true);
  }

  @Test
  public void interruptDuringLock() throws Throwable {
    interruptDuringLockOrUpgrade(false);
  }

  private void interruptDuringLockOrUpgrade(final boolean aIsUpgrade) throws Throwable {
    ResultThread<Boolean> mThread = new ResultThread<>(new Callable<Boolean>() {
      @Override
      public Boolean call() {
        if (aIsUpgrade) myLock.lock(Mode.UPGRADABLE);
        try {
          if (aIsUpgrade) myLock.upgradeInterruptibly();
          else myLock.lockInterruptibly(Mode.WRITE);
          return false;
        } catch (InterruptedException e) {
          assertFalse("interruption not cleared", Thread.interrupted());
          return true;
        }
      }
    });
    lockPermanently(Mode.READ);
    mThread.start();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    mThread.interrupt();
    assertTrue("Thread not interrupted", mThread.get());
  }

  @Test(expected=InterruptedException.class)
  public void interruptBeforeLock() throws Throwable {
    try {
      Thread.currentThread().interrupt();
      myLock.lockInterruptibly(Mode.UPGRADABLE);
      fail("hold succeeded");
    } finally {
      assertFalse(Thread.interrupted());
      assertTrue(isUnlocked());
    }
  }

  @Test(expected=InterruptedException.class)
  public void interruptBeforeUpgrade() throws Throwable {
    try {
      myLock.lock(Mode.UPGRADABLE);
      Thread.currentThread().interrupt();
      myLock.upgradeInterruptibly();
      fail("upgrade succeeded");
    } finally {
      assertFalse(Thread.interrupted());
      assertTrue(hasUpgradable());
    }
  }

  @Test(expected=InterruptedException.class)
  public void interruptBeforeReentrantLock() throws Throwable {
    try {
      myLock.lock(Mode.READ);
      Thread.currentThread().interrupt();
      myLock.lockInterruptibly(Mode.READ);
      fail("hold succeeded");
    } finally {
      myLock.unlock();
      assertTrue(isUnlocked());
    }
  }

  @Test
  public void ignoreInterruptionWithLock() {
    Thread.currentThread().interrupt();
    myLock.lock(Mode.WRITE);
    assertTrue(Thread.interrupted());
  }

  @Test
  public void retainInterruptedStatusWithUpgrade() throws Throwable {
    retainInterruptedStatusWithLockOrUpgrade(true);
  }

  @Test
  public void retainInterruptedStatusWithLock() throws Throwable {
    retainInterruptedStatusWithLockOrUpgrade(false);
  }

  private void retainInterruptedStatusWithLockOrUpgrade(final boolean aIsUpgrade) throws Throwable {
    ResultThread<Void> mThread = new ResultThread<>(new Runnable() {
      @Override
      public void run() {
        if (aIsUpgrade) {
          myLock.lock(Mode.UPGRADABLE);
          myLock.upgrade();
        } else myLock.lock(Mode.WRITE);
        assertTrue(Thread.interrupted());
        myLock.unlock();
      }
    });
    myLock.lock(Mode.READ);
    mThread.start();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    mThread.interrupt();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    myLock.unlock();
    mThread.get();
  }

  @Test
  public void retainInterruptedStatusWithTryLock() {
    Thread.currentThread().interrupt();
    assertTrue(myLock.tryLock(Mode.WRITE));
    myLock.unlock();
    assertTrue(Thread.interrupted());
  }

  @Test
  public void retainInterruptedStatusWithFailedTryLock() throws Throwable {
    lockPermanently(Mode.READ);
    Thread.currentThread().interrupt();
    assertFalse(myLock.tryLock(Mode.WRITE));
    assertTrue(Thread.interrupted());
  }

  @Test
  public void tryUpgradeWhenAvailable() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    assertTrue(myLock.tryUpgrade());
    assertTrue(hasWriter());
  }

  @Test
  public void tryUpgradeWhenNotAvailable() throws Throwable {
    lockPermanently(Mode.READ);
    myLock.lock(Mode.UPGRADABLE);
    assertFalse(myLock.tryUpgrade());
    assertTrue(hasUpgradable());
  }

  @Test
  public void tryUpgradeWithTimeout() throws Throwable {
    lockPermanently(Mode.READ);
    myLock.lock(Mode.UPGRADABLE);
    long mTime = 10_000_000;
    long mStart = System.nanoTime();
    assertFalse(myLock.tryUpgrade(mTime, TimeUnit.NANOSECONDS));
    assertTrue(System.nanoTime() >= mTime + mStart);
    assertTrue(hasUpgradable());
  }

  @Test
  public void enforceFifoWithFairLock() throws Throwable {
    myLock = new UpgradableLock(true);
    lockPermanently(Mode.UPGRADABLE);
    ResultThread<Void> mWaitingThread = newWaitingThread(Mode.UPGRADABLE);
    mWaitingThread.start();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    assertFalse("Barged ahead of thread waiting for upgradable", myLock.tryLock(Mode.READ));
    // check other thread for errors
    mWaitingThread.interrupt();
    mWaitingThread.get();
  }
  
  @Test
  public void allowBargingWithNonFairLock() throws Throwable {
    myLock = new UpgradableLock(false);
    lockPermanently(Mode.UPGRADABLE);
    ResultThread<Void> mWaitingThread = newWaitingThread(Mode.UPGRADABLE);
    mWaitingThread.start();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    assertTrue("Could not lock read", myLock.tryLock(Mode.READ));
    // check other thread for errors
    mWaitingThread.interrupt();
    mWaitingThread.get();
  }
  
  private ResultThread<Void> newWaitingThread(final Mode aLockMode) {
    return new ResultThread<>(new Runnable() {
      @Override
      public void run() {
        try {
          myLock.lockInterruptibly(aLockMode);
          fail("hold succeeded");
        } catch (InterruptedException e) {
          // return
        }
      }
    });
  }
  
  /*
   * Multiple reader threads wait for a lock held for writing. After the lock is
   * released, the reader threads wait on a barrier while holding the lock. This
   * ensures that all of the reader threads are signaled by only a single
   * release of lock.
   */
  @Test
  public void signalMultipleReaders() throws Throwable {
    myLock.lock(Mode.WRITE);
    int mNThreads = 5;
    final CyclicBarrier mBarrier = new CyclicBarrier(mNThreads);
    ExecutorService mPool = Executors.newCachedThreadPool();
    Collection<Future<?>> mFutures = new ArrayList<>();
    mFutures.add(mPool.submit(newBarrierTask(mBarrier, Mode.UPGRADABLE)));
    for (int i = 0; i < mNThreads - 1; i++) {
      mFutures.add(mPool.submit(newBarrierTask(mBarrier, Mode.READ)));
    }
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    myLock.unlock();
    for (Future<?> mFuture : mFutures) {
      getFromFuture(mFuture);
    }
    assertTrue(isUnlocked());
  }
  
  /*
   * Several threads with read locks and one thread with an upgradable lock
   * wait on a barrier.
   */
  @Test
  public void allowConcurrentReadAndUpgradableAccess() throws Throwable {
    int mNThreads = 4;
    final CyclicBarrier mBarrier = new CyclicBarrier(mNThreads);
    ExecutorService mPool = Executors.newCachedThreadPool();
    Collection<Future<?>> mFutures = new ArrayList<>();
    for (int i = 0; i < mNThreads - 1; i++) {
      mFutures.add(mPool.submit(newBarrierTask(mBarrier, Mode.READ)));
    }
    mFutures.add(mPool.submit(newBarrierTask(mBarrier, Mode.UPGRADABLE)));
    for (Future<?> mFuture : mFutures) {
      getFromFuture(mFuture);
    }
    assertTrue(isUnlocked());
  }
  
  private Runnable newBarrierTask(
      final CyclicBarrier aBarrier,
      final Mode aMode) {
    return new Runnable() {
      @Override
      public void run() {
        myLock.lock(aMode);
        try {
          aBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
          throw new RuntimeException(e);
        } finally {
          myLock.unlock();
        }
      }
    };
  }

  @Test
  public void serializeWithWriter() throws Throwable {
    lockPermanently(Mode.WRITE);
    byte[] mSerializedLock = serialize(myLock);
    assertTrue(hasWriter());
    myLock = (UpgradableLock) deserialize(mSerializedLock);
    assertTrue(isUnlocked());
  }
  
  @Test
  public void serializeWithReaders() throws Throwable {
    Mode[] mModes = {Mode.READ, Mode.UPGRADABLE};
    for (Mode mMode : mModes) {
      lockPermanently(mMode);
      byte[] mSerializedLock = serialize(myLock);
      assertFalse(canLock(Mode.WRITE));
      myLock = (UpgradableLock) deserialize(mSerializedLock);
      assertTrue(isUnlocked());
    }
  }
  
  private static byte[] serialize(Serializable aValue) throws IOException {
    ByteArrayOutputStream mOS = new ByteArrayOutputStream();
    ObjectOutputStream mOOS = new ObjectOutputStream(mOS);
    mOOS.writeObject(aValue);
    return mOS.toByteArray();
  }
  
  private static Serializable deserialize(byte[] aSerialized) throws IOException, ClassNotFoundException {
    InputStream mIS = new ByteArrayInputStream(aSerialized);
    ObjectInputStream mOIS = new ObjectInputStream(mIS);
    return (Serializable) mOIS.readObject();
  }
  
  /*
   * Multiple threads start, with a pause after each one. They each wait for a
   * lock that is already locked. The threads use different locking modes, with
   * every other thread locking for writing. This test ensures that when the
   * lock is released, the threads acquire the lock in the same order that they
   * were started, with no overlap.
   */
  @Test
  public void testFirstInFirstOut() throws Throwable {
    myLock.lock(Mode.WRITE);
    final List<Integer> mNumbers = new ArrayList<>();
    final List<Integer> mExpected = new ArrayList<>();
    ExecutorService mPool = Executors.newCachedThreadPool();
    Collection<Future<?>> mFutures = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final int mNumber = i;
      mExpected.add(mNumber);
      mFutures.add(mPool.submit(new Runnable() {
        @Override
        public void run() {
          switch (mNumber % 4) {
            case 0: case 2: myLock.lock(Mode.WRITE); break;
            case 1: myLock.lock(Mode.UPGRADABLE); break;
            case 3: myLock.lock(Mode.READ); break;
            default: throw new AssertionError();
          }
          mNumbers.add(mNumber);
          myLock.unlock();
        }
      }));
      Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    }
    myLock.unlock();
    // ensure that interruption does not affect the order
    mPool.shutdownNow();
    for (Future<?> mFuture : mFutures) {
      getFromFuture(mFuture);
    }
    Assert.assertEquals(mExpected, mNumbers);
  }
  
  /*
   * One thread tries to acquire the write lock multiple times while several
   * threads use a counter to try to trade off acquiring the read lock.
   */
  @Test
  public void preventWriterStarvation() throws Throwable {
    myLock = new UpgradableLock(false);
    Mode[] mModes = {Mode.WRITE, Mode.UPGRADABLE};
    for (Mode mMode : mModes) {
      final AtomicInteger mCounter = new AtomicInteger();
      ExecutorService mPool = Executors.newCachedThreadPool();
      Collection<Future<?>> mFutures = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        mFutures.add(mPool.submit(new Runnable() {
          @Override
          public void run() {
            try {
              while (true) {
                myLock.lockInterruptibly(Mode.READ);
                try {
                  int mStartCount = mCounter.incrementAndGet();
                  long mEndTime = System.nanoTime() + 5_000_000;
                  while (mCounter.get() == mStartCount && System.nanoTime() < mEndTime) {
                    Thread.sleep(0, 1_000);
                  }
                } finally {
                  myLock.unlock();
                }
              }
            } catch (InterruptedException e) {
              // return
            }
          }
        }));
      }
      for (int i = 0; i < 5; i++) {
        int mCount = mCounter.get();
        while (mCount == mCounter.get()) {
          // wait for readers to acquire lock
        }
        myLock.lock(mMode);
        myLock.upgrade();
        myLock.unlock();
      }
      mPool.shutdownNow();
      for (Future<?> mFuture : mFutures) {
        getFromFuture(mFuture);
      }
      assertTrue(isUnlocked());
    }
  }
  
  @Test
  public void releaseWriteWithWaitingThreads() throws Throwable {
    Mode[] mModes = {Mode.UPGRADABLE, Mode.WRITE};
    for (Mode mMode : mModes) {
      ResultThread<Void> mThread = new ResultThread<>(new Runnable() {
        @Override
        public void run() {
          myLock.lock(Mode.READ);
          myLock.unlock();
        }
      });
      myLock.lock(mMode);
      myLock.upgrade();
      mThread.start();
      Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
      myLock.downgrade();
      if (mMode == Mode.WRITE) myLock.unlock();
      mThread.get();
      if (mMode == Mode.UPGRADABLE) myLock.unlock();
      assertTrue(isUnlocked());
    }
  }
  
  @Test
  public void releaseReadWithWaitingThreads() throws Throwable {
    Mode[] mUnlockModes = {Mode.UPGRADABLE, Mode.READ};
    Mode[] mLockModes = {Mode.UPGRADABLE, Mode.WRITE};
    for (Mode mUnlockMode : mUnlockModes) {
      for (final Mode mLockMode : mLockModes) {
        ResultThread<Void> mThread = new ResultThread<>(new Runnable() {
          @Override
          public void run() {
            myLock.lock(mLockMode);
            myLock.upgrade();
            myLock.unlock();
          }
        });
        myLock.lock(mUnlockMode);
        mThread.start();
        Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
        myLock.unlock();
        mThread.get();
        assertTrue(isUnlocked());
      }
    }
  }
  
  /*
   * Thread 1 locks upgradable. Then Thread 2 waits for upgradable. Thread 3
   * temporarily locks read while Thread 1 tries to upgrade, ensuring that
   * Thread 1 blocks. This could cause a deadlock if Thread 3 signals the
   * longest waiting thread, instead of the thread waiting to upgrade.
   * Thread 1 should unblock and upgrade.
   */
  @Test
  public void upgradeAfterBlocking() throws Throwable {
    myLock = new UpgradableLock(false);
    ResultThread<Void> mThread2 = new ResultThread<>(new Runnable() {
      @Override
      public void run() {
        myLock.lock(Mode.UPGRADABLE);
        myLock.unlock();
      }
    });
    final CyclicBarrier mBarrier = new CyclicBarrier(2);
    ResultThread<Void> mThread3 = new ResultThread<>(new Runnable() {
      @Override
      public void run() {
        try {
          myLock.lock(Mode.READ);
          mBarrier.await();
          Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
          myLock.unlock();
        } catch (InterruptedException | BrokenBarrierException e) {
          throw new RuntimeException(e);
        }
      }
    });
    myLock.lock(Mode.UPGRADABLE);
    mThread2.start();
    Thread.sleep(MAX_WAIT_FOR_LOCK_MILLIS);
    mThread3.start();
    mBarrier.await();
    myLock.upgrade();
    mThread3.get();
    // let Thread 2 unblock and check it for uncaught exceptions
    myLock.unlock();
    mThread2.get();
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventLockingWriteAfterRead() throws Throwable {
    testLockingAfterRead(Mode.WRITE);
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventLockingUpgradableAfterRead() throws Throwable {
    testLockingAfterRead(Mode.UPGRADABLE);
  }

  private void testLockingAfterRead(Mode aMode) throws Throwable {
    myLock.lock(Mode.READ);
    try {
      myLock.lock(aMode);
    } finally {
      assertTrue(hasReaders());
      myLock.unlock();
      assertTrue(isUnlocked());
    }
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventUpgradeWithoutLock() throws Throwable {
    try {
      myLock.upgrade();
    } finally {
      assertTrue(isUnlocked());
    }
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventUpgradeFromRead() throws Throwable {
    try {
      myLock.lock(Mode.READ);
      myLock.upgrade();
    } finally {
      assertTrue(hasReaders());
    }
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventDowngradeWithoutUpgrade() throws Throwable {
    myLock.lock(Mode.UPGRADABLE);
    try {
      myLock.downgrade();
    } finally {
      assertTrue(hasUpgradable());
      myLock.unlock();
      assertTrue(isUnlocked());
    }
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventUnlockWithoutLock() throws Throwable {
    try {
      myLock.unlock();
    } finally {
      assertTrue(isUnlocked());
    }
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventDowngradeWithoutLock() {
    myLock.downgrade();
  }

  @Test(expected=IllegalMonitorStateException.class)
  public void preventDowngradeFromRead() throws Throwable {
    try {
      myLock.lock(Mode.READ);
      myLock.downgrade();
    } finally {
      assertTrue(hasReaders());
    }
  }

  @Test(expected=NullPointerException.class)
  public void disallowNullLockModeWithLock() {
    myLock.lock(null);
  }
  
  @Test(expected=NullPointerException.class)
  public void disallowNullLockModeWithTryLock() {
    myLock.tryLock(null);
  }
  
  @Test(expected=NullPointerException.class)
  public void disallowNullTimeUnitWithLock() throws InterruptedException {
    myLock.tryLock(Mode.READ, 10, null);
  }
  
  @Test(expected=NullPointerException.class)
  public void disallowNullTimeUnitWithUpgrade() throws InterruptedException {
    myLock.lock(Mode.UPGRADABLE);
    myLock.tryUpgrade(-10, null);
  }
  
  private void lockPermanently(final Mode aMode) throws Throwable {
    ResultThread<Boolean> mThread = new ResultThread<>(new Callable<Boolean>() {
      @Override
      public Boolean call() {
        return myLock.tryLock(aMode);
      }
    });
    mThread.start();
    assertTrue(mThread.get());
  }

  private boolean isUnlocked() throws Throwable {
    return canLock(Mode.WRITE);
  }
  
  private boolean hasReaders() throws Throwable {
    return !canLock(Mode.WRITE) && canLock(Mode.UPGRADABLE);
  }
  
  private boolean hasUpgradable() throws Throwable {
    return !canLock(Mode.UPGRADABLE) && canLock(Mode.READ);
  }
  
  private boolean hasWriter() throws Throwable {
    return !canLock(Mode.READ);
  }

  private boolean canLock(final Mode aMode) throws Throwable {
    ResultThread<Boolean> mThread = new ResultThread<>(new Callable<Boolean>() {
      @Override
      public Boolean call() {
        boolean mSuccess = myLock.tryLock(aMode);
        if (mSuccess) myLock.unlock();
        return mSuccess;
      }
    });
    mThread.start();
    return mThread.get();
  }
  
  private static <T> T getFromFuture(Future<T> aFuture) throws Throwable {
    try {
      return aFuture.get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }
  
  /**
   * Future-like object that allows the main thread to easily wait for
   * termination, get a result value, and check for uncaught exceptions by
   * calling get().
   */
  private static final class ResultThread<T> {
    private final RunnableFuture<T> myFuture;
    private final Thread myThread;

    ResultThread(final Callable<T> aCallable) {
      myFuture = new FutureTask<>(aCallable);
      myThread = new Thread(myFuture);
    }

    ResultThread(final Runnable aRunnable) {
      myFuture = new FutureTask<T>(aRunnable, null);
      myThread = new Thread(myFuture);
    }
    
    void start() {
      myThread.start();
    }
    
    public void interrupt() {
      myThread.interrupt();
    }
    
    T get() throws Throwable {
      try {
        return myFuture.get();
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    }
  }
}
