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
package org.eclipse.rcptt.runner.util;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.pde.internal.launching.IPDEConstants;
import org.eclipse.pde.internal.launching.launcher.VMHelper;
import org.eclipse.pde.launching.IPDELauncherConstants;
import org.eclipse.rcptt.internal.core.RcpttPlugin;
import org.eclipse.rcptt.internal.launching.Q7LaunchManager.SessionRunnable;
import org.eclipse.rcptt.internal.launching.aut.BaseAutLaunch;
import org.eclipse.rcptt.internal.launching.ext.JDTUtils;
import org.eclipse.rcptt.internal.launching.ext.OSArchitecture;
import org.eclipse.rcptt.internal.launching.ext.Q7ExtLaunchingPlugin;
import org.eclipse.rcptt.internal.launching.ext.Q7TargetPlatformManager;
import org.eclipse.rcptt.launching.Aut;
import org.eclipse.rcptt.launching.AutLaunchState;
import org.eclipse.rcptt.launching.AutManager;
import org.eclipse.rcptt.launching.IQ7Launch;
import org.eclipse.rcptt.launching.ext.Q7ExternalLaunchDelegate;
import org.eclipse.rcptt.launching.ext.Q7LaunchDelegateUtils;
import org.eclipse.rcptt.launching.ext.Q7LaunchingUtil;
import org.eclipse.rcptt.launching.ext.VmInstallMetaData;
import org.eclipse.rcptt.launching.rap.RAPLaunchConfig;
import org.eclipse.rcptt.launching.target.ITargetPlatformHelper;
import org.eclipse.rcptt.runner.HeadlessRunner;
import org.eclipse.rcptt.runner.HeadlessRunnerPlugin;
import org.eclipse.rcptt.runner.PrintStreamMonitor;
import org.eclipse.rcptt.runner.RunnerConfiguration;
import org.eclipse.rcptt.runner.ScenarioRunnable;
import org.eclipse.rcptt.util.StringUtils;

import com.google.common.base.Strings;

@SuppressWarnings("restriction")
public class AutThread extends Thread {

	public BaseAutLaunch launch;
	public final int autId;
	public int restartId = 0;
	private String outFilePath;
	private boolean cancel = false;
	private final List<ScenarioRunnable> runnables;
	private ScenarioRunnable currentRunnable = null;

	private final AUTsManager manager;
	private final RunnerConfiguration conf;
	private final TargetPlatformChecker tpc;

	public AutThread(List<ScenarioRunnable> r, AUTsManager manager, RunnerConfiguration conf,
			TargetPlatformChecker tpc) {
		super("AUT-Worker-" + manager.autCounter.get());
		autId = manager.autCounter.getAndIncrement();
		outFilePath = String.format("%s%d_console.log", conf.autConsolePrefix == null
				? ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().getParentFile().getAbsolutePath()
				: conf.autConsolePrefix, autId);
		runnables = r;

		this.manager = manager;
		this.conf = conf;
		this.tpc = tpc;
	}

	/**
	 * Checks launch.
	 *
	 * If launch is NULL -> throw Exception If launch status is not ACTIVE ->
	 * restart AUT If launch status is ACTIVE -> ping launch -> restart AUT (if
	 * no successful ping for a certain time)
	 *
	 * @param worker
	 * @throws InterruptedException
	 * @throws CoreException
	 */
	private void checkAut() throws InterruptedException, CoreException {
		if (launch == null) {
			throw new RuntimeException("AUT is not available");
		}

		if (!AutLaunchState.ACTIVE.equals(launch.getState())) {
			HeadlessRunnerPlugin.getDefault().info("AUT is not active, restarting..");
			restart();
		} else {
			long startTime = System.currentTimeMillis();
			while (true) {
				try {
					launch.ping();
					break;
				} catch (CoreException e) {
					// Ping failed, waiting..
				}
				long currentTime = System.currentTimeMillis();
				if (currentTime > startTime + conf.waitAutTimeout * 1000) {
					// Waiting is too long.. enough
					HeadlessRunnerPlugin.getDefault().info("AUT is not answering, restarting..");
					restart();
					break;
				}
				sleep(100);
			}
		}
	}

	@Override
	public void run() {
		try {
			while (!runnables.isEmpty() && !cancel) {
				checkAut();

				synchronized (runnables) {
					currentRunnable = runnables.remove(0);
				}
				if (currentRunnable != null) {
					currentRunnable.run(this);
				}
			}
		} catch (InterruptedException e) {
			// Ignore
		} catch (CoreException e) {
			HeadlessRunnerPlugin.log(e.getStatus());
		}
	}

	/**
	 * the target platform should have been checked by {@link #tpc} before this
	 * method is called
	 */
	private ILaunchConfiguration createAUTLaunchConfiguration(String autWorkspace, int index) throws CoreException {
		// were synchronized by HeadlessRunner instance before,
		// but only used to synchronize different AutThread instances,
		// so we can use AUTsManager's monitor here
		synchronized (manager) {
			final String autArgs = Q7LaunchDelegateUtils.getAUTArgs(conf.autArgs);

			System.out.println(String.format("%s: AUT arguments: %s", autWorkspace, autArgs));

			ILaunchConfigurationWorkingCopy config;

			if (conf.rapPlatform) {

				config = createLaunchConfiguration(tpc.getTargetPlatform(), "AUT_" + Integer.toString(index), autArgs); //$NON-NLS-1$
				RAPLaunchConfig.setDefaults(config);
				final RAPLaunchConfig helper = new RAPLaunchConfig(config);

				if (conf.port != null) {
					helper.setPort(conf.port);
				}
				if (!Strings.isNullOrEmpty(conf.path)) {
					helper.setServletPath(conf.path);
				}

				if(!StringUtils.isEmpty(conf.browserCmd)){
					helper.setBrowserCommand(conf.browserCmd);
				}
			} else {
				config = Q7LaunchingUtil.createQ7LaunchConfiguration(tpc.getTargetPlatform(), autArgs,
						"AUT_" + Integer.toString(index));
			}

			config.setAttribute(IPDELauncherConstants.DOCLEAR, false);
			config.setAttribute(IPDELauncherConstants.ASKCLEAR, true);
			config.setAttribute(IPDEConstants.DOCLEARLOG, false);
			config.setAttribute(IPDELauncherConstants.LOCATION, autWorkspace);

			config.setAttribute(IQ7Launch.ATTR_HEADLESS_LAUNCH, true);
			config.setAttribute(DebugPlugin.ATTR_CAPTURE_OUTPUT, true);

			config.setAttribute(IQ7Launch.OVERRIDE_SECURE_STORAGE, conf.overrideSecurityStorage);

			config.setAttribute(IQ7Launch.ATTR_OUT_FILE, outFilePath);
			final String vmArgs = Q7LaunchDelegateUtils.getJoinedVMArgs(tpc.getTargetPlatform(),
					Arrays.asList(conf.autVMArgs));
			System.out.println(String.format("%s: AUT VM arguments: %s", autWorkspace, vmArgs));
			config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArgs);

			Optional<VmInstallMetaData> autVM = manager.getAutVm();
			if (autVM.isPresent()) {
				config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_JRE_CONTAINER_PATH, autVM.get().formatVmContainerPath());
				config.setAttribute(IPDEConstants.APPEND_ARGS_EXPLICITLY, true);
			}

			if (conf.enableSoftwareInstallation)
			{
				config.setAttribute(IPDELauncherConstants.GENERATE_PROFILE, true);
			}

			// Validate JVM compatibility
			boolean haveAUT = false;
			OSArchitecture architecture = tpc.getTargetPlatform().detectArchitecture(null);
			if (!architecture.equals(OSArchitecture.Unknown)) {
				IVMInstall install = Q7ExternalLaunchDelegate.getVMInstall(config, tpc.getTargetPlatform());
				try {
					OSArchitecture jvmArch = JDTUtils.detect(install);
					if (jvmArch.equals(architecture)) {
						haveAUT = true;
					}
				} catch (Throwable e) {
					RcpttPlugin.log(e);
				}
				if (!haveAUT) {
					// Let's search for configuration and update JVM if
					// possible.
					haveAUT = updateJVM(config, tpc.getTargetPlatform());

				}
				if (!haveAUT) {
					String errorMessage = "FAIL: AUT requires "
							+ architecture
							+ " Java VM. It is incompatible with " + tpc.getTargetPlatform().getIncompatibleExecutionEnvironments()
							+ ". Such VM cannot be found.\nPlease specify -autVM {javaPath} command line argument to use different JVM.\nCurrent used JVM is: "
							+ install.getInstallLocation().toString();
					Q7ExtLaunchingPlugin.getDefault().log(errorMessage, null);
					throw new CoreException(
							new Status(IStatus.ERROR, Q7ExtLaunchingPlugin.PLUGIN_ID, errorMessage, null));
				}
			}
			if (!architecture.equals(OSArchitecture.Unknown)) {
				// Update -arch to be correct
				try {
					String finalArgs = config
							.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, "");
					finalArgs = finalArgs.replace("${target.arch}", architecture.name());

					config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, finalArgs.toString());
				} catch (Throwable e) {
					throw new CoreException(
							new Status(IStatus.ERROR, Q7ExtLaunchingPlugin.PLUGIN_ID, e.getMessage(), e));
				}
			}

			return config.doSave();
		}
	}


	
	private boolean updateJVM(ILaunchConfigurationWorkingCopy configuration, ITargetPlatformHelper target) {
		try {
			StringBuilder error = new StringBuilder();
			OSArchitecture autArch = target.detectArchitecture(error);
			if (autArch == OSArchitecture.Unknown) {
				throw new CoreException(Status.error("Failed to detect AUT architecture: " + error));
			}
			final VmInstallMetaData jvmInstall = VmInstallMetaData.all().filter(i -> i.arch.equals(autArch)).findFirst().orElse(null);
			if (jvmInstall == null) {
				return false;
			}
			return Q7ExternalLaunchDelegate.updateJVM(configuration, autArch, target);
		} catch (Throwable e) {
			RcpttPlugin.log(e);
		}
		return false;
	}

	void launchAut() {
		if (restartId != 0) {
			HeadlessRunnerPlugin.getDefault()
					.info("AUT-" + autId + ":" + "Restarting " + " (restartId=" + restartId + ")");
			System.out.println("AUT-" + autId + ":" + "Restarting (restartId=" + restartId + ")");
		} else {
			HeadlessRunnerPlugin.getDefault().info("AUT-" + autId + ":" + "Launching");
			System.out.println("AUT-" + autId + ":" + "Launching");
		}
		System.out.println("AUT-" + autId + ":" + "Product: " + tpc.getTargetPlatform().getDefaultProduct());
		System.out.println("AUT-" + autId + ":" + "Application: " + tpc.getTargetPlatform().getDefaultApplication());

		StringBuilder archDetect = new StringBuilder();
		OSArchitecture architecture = tpc.getTargetPlatform().detectArchitecture(archDetect);
		System.out
				.println("AUT-" + autId + ":" + "Architecture: " + architecture.name() + "\n" + archDetect.toString());

		String restartPostfix;
		String autWorkspace;
		File dir;
		while (true) {
			restartPostfix = restartId == 0 ? "" : ("_restarted_" + Integer.valueOf(restartId));
			autWorkspace = getAutWorkspace(restartPostfix);
			dir = new File(autWorkspace);
			if (conf.reuseExistingWorkspace) {
				dir.mkdirs();
				break;
			}
			HeadlessRunner.deleteDir(dir);
			if (dir.exists()) {
				restartId++;
				continue;
			}
			dir.mkdirs(); // To be sure log file will be available
			break;
		}
		String prefix = conf.autConsolePrefix;
		if (Strings.isNullOrEmpty(prefix)) {
			prefix = "rcptt";
		}

		final File logFile2 = new File(prefix + autId + restartPostfix + "_startlog.log");
		try {
			if (logFile2.exists()) {
				logFile2.delete();
			}
			logFile2.createNewFile();
		} catch (IOException e) {
			throw new RuntimeException("AUT-" + autId + ":" + "Failed to create log file: " + e.getMessage(), e);
		}

		try {
			IProgressMonitor monitor = new PrintStreamMonitor("AUT-" + autId + ":" + restartPostfix, logFile2);
			ILaunchConfiguration savedConfig = createAUTLaunchConfiguration(autWorkspace, restartId);
			Aut aut = AutManager.INSTANCE.getByLaunch(savedConfig);
			if (aut == null) {
				throw new CoreException(new Status(IStatus.ERROR, HeadlessRunnerPlugin.PLUGIN_ID,
						"No AUT found for launch configuration: " + savedConfig));
			}
			launch = (BaseAutLaunch) aut.launch(monitor);
			if (launch == null) {
				throw new CoreException(new Status(IStatus.ERROR, HeadlessRunnerPlugin.PLUGIN_ID,
						"No launch for configuration: " + savedConfig));
			}
		} catch (final Exception e) {
			try {
				if (launch != null) {
					launch.terminate();
				}
			} catch (Exception e2) {
				System.out.println(String.format("AUT-%s Launch failed. Reason: %s", autId, e.getMessage()));
			}

			String errorMessage = String.format("AUT-%s: Launch failed. Reason: %s", autId, e.getMessage());
			System.out.println(errorMessage);
			System.out.println(
					String.format("AUT-%s: For more information check AUT output at '%s'", autId, outFilePath));
			HeadlessRunnerPlugin.getDefault().info(errorMessage);
			throw new RuntimeException(errorMessage, e);
		}
	}

	private String getAutWorkspace(String restartPostfix) {
		return conf.reuseExistingWorkspace ? conf.autWorkspacePrefix : conf.autWorkspacePrefix + autId + restartPostfix;
	}

	public void shutdown() throws CoreException, InterruptedException {
		if (launch != null) {
			System.out.printf("Initiating shutdown. AUT is currently %s\n", launch.getLaunch().isTerminated() ? "terminated" : "running");
			try {
				launch.gracefulShutdown(conf.shutdownTimeout);
			} catch (TimeoutException e) {
				launch.terminate();
				System.out.printf("Failed to gracefully shutdown AUT in " + conf.shutdownTimeout + "ms. Terminating.");
			}
			launch = null;
		}
	}

	private static ILaunchConfigurationWorkingCopy createLaunchConfiguration(ITargetPlatformHelper target, String name,
			String args) throws CoreException {
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = launchManager.getLaunchConfigurationType("org.eclipse.rcptt.launching.rap");
		if (name == null) {
			name = launchManager.generateLaunchConfigurationName("AUT");
		}
		final ILaunchConfigurationWorkingCopy config = type.newInstance(null, name);
		config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, Q7LaunchDelegateUtils
				.getWorkingDirectory(new File(target.getTargetPlatformProfilePath())).getAbsolutePath());

		config.setAttribute(IQ7Launch.AUT_LOCATION, target.getTargetPlatformProfilePath());

		config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, args);
		Q7TargetPlatformManager.setHelper(config, target);
		return config;
	}

	public void restart() throws CoreException, InterruptedException {
		shutdown();
		restartId++;
		launchAut();
	}

	/**
	 * Put the current {@link SessionRunnable} back in the queue so that it is
	 * run again.
	 */
	void retry() {
		if (currentRunnable != null) {
			synchronized (runnables) {
				runnables.add(0, currentRunnable);
			}
			currentRunnable = null;
		}
	}

	public void cancel() {
		this.cancel = true;
	}

	@Override
	public String toString() {
		return "[AUT-" + autId + "]";
	}

}
