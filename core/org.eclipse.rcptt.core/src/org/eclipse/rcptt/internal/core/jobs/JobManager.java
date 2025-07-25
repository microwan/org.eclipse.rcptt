/*******************************************************************************
 * Copyright (c) 2009, 2019 Xored Software Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     Xored Software Inc - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.rcptt.internal.core.jobs;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.osgi.util.NLS;

public abstract class JobManager {

	/* queue of jobs to execute */
	protected IJob[] awaitingJobs = new IJob[10];
	protected int jobStart = 0;
	protected int jobEnd = -1;
	protected boolean executing = false;

	/* background processing */
	protected final Thread processingThread = new Thread(this::run, this.processName());
	{
		this.processingThread.setDaemon(true);
		// less prioritary by default, priority is raised if clients are
		// actively waiting on it
		this.processingThread.setPriority(Thread.NORM_PRIORITY - 1);
	}
	
	private final AtomicReference<Throwable> error = new AtomicReference<>();

	/*
	 * counter indicating whether job execution is enabled or not, disabled if
	 * <= 0 it cannot go beyond 1
	 */
	private int enableCount = 1;

	private int awaitingClients = 0;

	/**
	 * Answer the amount of awaiting jobs.
	 */
	private synchronized int awaitingJobsCount() {
		// pretend busy in case concurrent job attempts performing before
		// activated
		return processingThread.isAlive() ? this.jobEnd - this.jobStart + 1 : 1;
	}

	/**
	 * Answers the first job in the queue, or null if there is no job available
	 * Until the job has completed, the job manager will keep answering the same
	 * job.
	 */
	public synchronized IJob currentJob() {
		if (this.enableCount > 0 && this.jobStart <= this.jobEnd)
			return this.awaitingJobs[this.jobStart];
		return null;
	}

	public synchronized void disable() {
		this.enableCount--;
	}

	/**
	 * Remove the index from cache for a given project. Passing null as a job
	 * family discards them all.
	 * @throws InterruptedException 
	 */
	public void discardJobs(String jobFamily) throws InterruptedException {
		try {
			IJob currentJob;
			// cancel current job if it belongs to the given family
			synchronized (this) {
				currentJob = this.currentJob();
				disable();
			}
			if (currentJob != null
					&& (jobFamily == null || currentJob.belongsTo(jobFamily))) {
				currentJob.cancel();

				// wait until current active job has finished
				synchronized (this) {
					while (this.processingThread.isAlive() && this.executing) {
						this.wait(50);
					}
				}
			}

			// flush and compact awaiting jobs
			int loc = -1;
			synchronized (this) {
				for (int i = this.jobStart; i <= this.jobEnd; i++) {
					currentJob = this.awaitingJobs[i];
					if (currentJob != null) { // sanity check
						this.awaitingJobs[i] = null;
						if (!(jobFamily == null || currentJob
								.belongsTo(jobFamily))) { // copy down,
							// compacting
							this.awaitingJobs[++loc] = currentJob;
						} else {
							currentJob.cancel();
						}
					}
				}
				this.jobStart = 0;
				this.jobEnd = loc;
			}
		} finally {
			enable();
		}
	}

	public synchronized void enable() {
		this.enableCount++;
		this.notifyAll(); // wake up the background thread if it is waiting
		// (context must be synchronized)
	}

	public synchronized boolean isJobWaiting(IJob request) {
		for (int i = this.jobEnd; i > this.jobStart; i--)
			// don't check job at jobStart, as it may have already started
			if (request.equals(this.awaitingJobs[i]))
				return true;
		return false;
	}

	/**
	 * Advance to the next available job, once the current one has been
	 * completed. Note: clients awaiting until the job count is zero are still
	 * waiting at this point.
	 */
	protected synchronized void moveToNextJob() {
		// if (!enabled) return;

		if (this.jobStart <= this.jobEnd) {
			this.awaitingJobs[this.jobStart++] = null;
			if (this.jobStart > this.jobEnd) {
				this.jobStart = 0;
				this.jobEnd = -1;
			}
		}
	}

	/**
	 * When idle, give chance to do something
	 */
	protected void notifyIdle(long idlingTime) {
		// do nothing
	}

	/**
	 * This API is allowing to run one job in concurrence with background
	 * processing. Indeed since other jobs are performed in background, resource
	 * sharing might be an issue.Therefore, this functionality allows a given
	 * job to be run without colliding with background ones. Note: multiple
	 * thread might attempt to perform concurrent jobs at the same time, and
	 * should synchronize (it is deliberately left to clients to decide whether
	 * concurrent jobs might interfere or not. In general, multiple read jobs
	 * are ok).
	 * 
	 * Waiting policy can be: IJobConstants.ForceImmediateSearch
	 * IJobConstants.CancelIfNotReadyToSearch
	 * IJobConstants.WaitUntilReadyToSearch
	 * 
	 */
	public boolean performConcurrentJob(IJob searchJob, int waitingPolicy,
			IProgressMonitor progress) {
		return performConcurrentJob(searchJob, waitingPolicy, progress, -1);
	}

	public boolean performConcurrentJob(IJob searchJob, int waitingPolicy,
			IProgressMonitor progress, long timeout) {

		searchJob.ensureReadyToRun();

		start();
		int concurrentJobWork = 100;
		if (progress != null)
			progress.beginTask("", concurrentJobWork); //$NON-NLS-1$
		boolean status = IJob.FAILED;
		if (awaitingJobsCount() > 0) {
			switch (waitingPolicy) {

			case IJob.ForceImmediate:
				try {
					disable(); // pause indexing
					status = searchJob.execute(progress == null ? null
							: new SubProgressMonitor(progress,
									concurrentJobWork));
				} finally {
					enable();
				}
				return status;

			case IJob.CancelIfNotReady:
				throw new OperationCanceledException();

			case IJob.WaitUntilReady:
				long start = System.currentTimeMillis();
				IProgressMonitor subProgress = null;
				int totalWork = this.awaitingJobsCount();
				if (progress != null && totalWork > 0) {
					subProgress = new SubProgressMonitor(progress,
							concurrentJobWork / 2);
					subProgress.beginTask("", totalWork); //$NON-NLS-1$
					concurrentJobWork = concurrentJobWork / 2;
				}
				if (totalWork > 0) {
					synchronized (delaySignal) {
						delaySignal.notify();
					}
				}
				Thread t = this.processingThread;
				int originalPriority = t.isAlive() ? t.getPriority() : -1;
				try {
					if (t != null)
						t.setPriority(Thread.currentThread().getPriority());
					synchronized (this) {
						this.awaitingClients++;
					}
					IJob previousJob = null;
					int awaitingWork;
					while ((awaitingWork = awaitingJobsCount()) > 0
							&& (timeout == -1 || (System.currentTimeMillis() - start) < timeout)) {
						if (subProgress != null && subProgress.isCanceled())
							throw new OperationCanceledException();
						final IJob currentJob = currentJob();
						// currentJob can be null when jobs have been added to
						// the queue but job manager is not enabled
						if (currentJob != null && currentJob != previousJob) {
							if (subProgress != null) {
								subProgress.subTask(NLS.bind(
										"Files to index ${0}",
										Integer.toString(awaitingWork)));
								subProgress.worked(1);
							}
							previousJob = currentJob;
						}
						Thread.sleep(50);
					}
					if (timeout != -1
							&& (System.currentTimeMillis() - start) > timeout) {
						return false;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new OperationCanceledException();
				} finally {
					synchronized (this) {
						this.awaitingClients--;
					}
					if (t != null && originalPriority > -1 && t.isAlive())
						t.setPriority(originalPriority);
				}
				if (subProgress != null)
					subProgress.done();
			}
		}
		status = searchJob.execute(progress == null ? null
				: new SubProgressMonitor(progress, concurrentJobWork));
		if (progress != null)
			progress.done();
		return status;
	}

	public abstract String processName();

	private static final class WaitJob implements IJob {
		@Override
		public boolean belongsTo(String jobFamily) {
			return false;
		}

		@Override
		public void cancel() {
		}

		@Override
		public void ensureReadyToRun() {
		}

		@Override
		public boolean execute(IProgressMonitor progress) {
			return false;
		}

		@Override
		public String toString() {
			return "WAIT-UNTIL-READY-JOB"; //$NON-NLS-1$
		}
	}

	public void waitUntilReady(IProgressMonitor monitor) {
		performConcurrentJob(new WaitJob(), IJob.WaitUntilReady, monitor);
	}

	/**
	 * @since 2.0
	 */
	public void requestIfNotWaiting(IJob job) {
		if (!isJobWaiting(job)) {
			request(job);
		}
	}

	public synchronized void request(IJob job) {

		job.ensureReadyToRun();

		// append the job to the list of ones to process later on
		int size = this.awaitingJobs.length;
		if (++this.jobEnd == size) { // when growing, relocate jobs starting at
			// position 0
			this.jobEnd -= this.jobStart;
			System.arraycopy(this.awaitingJobs, this.jobStart,
					this.awaitingJobs = new IJob[size * 2], 0, this.jobEnd);
			this.jobStart = 0;
		}
		this.awaitingJobs[this.jobEnd] = job;
		notifyAll(); // wake up the background thread if it is waiting
	}

	/**
	 * Flush current state
	 */
	public synchronized void reset() throws InterruptedException {
		discardJobs(null); // discard all jobs
		error.set(null);
		start();
	}

	private synchronized void start() {
		/* initiate background processing */
		if (!this.processingThread.isAlive()) {
			this.processingThread.start();
		}
	}

	/**
	 * is used for delaying before processing new jobs, that could be canceled
	 * by {@link #performConcurrentJob()} if called with
	 * {@link IJob#WaitUntilReady}
	 */
	private final Object delaySignal = new Object();

	/**
	 * Infinite loop performing resource indexing
	 */
	private void run() {
		long idlingStart = -1;
		try {
			while (this.processingThread != null) {
					IJob job;
					synchronized (this) {
						// handle shutdown case when notifyAll came before the
						// wait but after the while loop was entered
						if (this.processingThread == null)
							continue;

						// must check for new job inside this sync block to
						// avoid timing hole
						if ((job = currentJob()) == null) {
							if (idlingStart < 0)
								idlingStart = System.currentTimeMillis();
							else
								notifyIdle(System.currentTimeMillis()
										- idlingStart);
							this.wait(); // wait until a new job is posted (or
							// reenabled:38901)
						} else {
							idlingStart = -1;
						}
					}
					if (job == null) {
						notifyIdle(System.currentTimeMillis() - idlingStart);
						// just woke up, delay before processing any new jobs,
						// allow some time for the active thread to finish
						synchronized (delaySignal) {
							delaySignal.wait(500);
						}
						continue;
					}
					try {
						synchronized (this) {
							this.executing = true;
						}
						/* boolean status = */job.execute(null);
						// if (status == FAILED) request(job);
					} finally {
						synchronized (this) {
							this.executing = false;
							this.notifyAll();
						}
						moveToNextJob();
						boolean waitAC = false;
						synchronized (JobManager.this) {
							waitAC = this.awaitingClients == 0;
						}
						if (waitAC)
							Thread.sleep(50);
					}
			}
		} catch (Throwable e) {
			error.compareAndSet(null, e);
		}
	}

	@Override
	public String toString() {
		StringBuffer buffer = new StringBuffer(64);
		buffer.append("Enable count:").append(this.enableCount).append('\n'); //$NON-NLS-1$
		int numJobs = this.jobEnd - this.jobStart + 1;
		buffer.append("Jobs in queue:").append(numJobs).append('\n'); //$NON-NLS-1$
		for (int i = 0; i < numJobs && i < 15; i++) {
			buffer.append(i)
					.append(" - job[" + i + "]: ").append(this.awaitingJobs[this.jobStart + i]).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return buffer.toString();
	}
}
