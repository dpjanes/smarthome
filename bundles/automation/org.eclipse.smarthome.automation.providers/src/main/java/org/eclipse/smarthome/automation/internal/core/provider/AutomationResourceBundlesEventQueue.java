/**
 * Copyright (c) 1997, 2015 by ProSyst Software GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.automation.internal.core.provider;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.smarthome.automation.Rule;
import org.eclipse.smarthome.automation.template.Template;
import org.eclipse.smarthome.automation.template.TemplateProvider;
import org.eclipse.smarthome.automation.type.ModuleType;
import org.eclipse.smarthome.automation.type.ModuleTypeProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for tracking the bundles providing automation resources and delegating the processing to
 * the responsible providers in separate thread.
 *
 * @author Ana Dimova - Initial Contribution, host-fragment support
 * @author Kai Kreuzer - refactored (managed) provider and registry implementation
 *
 */
@SuppressWarnings("deprecation")
class AutomationResourceBundlesEventQueue implements Runnable, BundleTrackerCustomizer<Object> {

    /**
     * This static field serves as criteria for recognizing bundles providing automation resources. If these bundles
     * have such manifest header this means that they are such providers.
     */
    public static final String AUTOMATION_RESOURCES_HEADER = "Automation-ResourceType";

    /**
     * This field keeps instance of {@link Logger} that is used for logging.
     */
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * This field serves for saving the bundles providing automation resources until their processing completes.
     */
    private List<BundleEvent> queue = new ArrayList<BundleEvent>();

    /**
     * This field is for synchronization purposes
     */
    private boolean running = false;

    /**
     * This field is for synchronization purposes
     */
    private boolean closed = false;

    /**
     * This field is for synchronization purposes
     */
    private boolean shared = false;

    /**
     * This field is a bundle tracker for bundles providing automation resources.
     */
    private BundleTracker<Object> bTracker;

    /**
     * This field holds a reference to an implementation of {@link TemplateProvider}.
     */
    private AbstractResourceBundleProvider<Template> tProvider;

    /**
     * This field holds a reference to an implementation of {@link ModuleTypeProvider}.
     */
    private AbstractResourceBundleProvider<ModuleType> mProvider;

    /**
     * This field holds a reference to an importer for {@link Rule}s.
     */
    private AbstractResourceBundleProvider<Rule> rImporter;

    private Map<Bundle, List<Bundle>> hostFragmentMapping = new HashMap<Bundle, List<Bundle>>();

    private PackageAdmin pkgAdmin;

    /**
     * This constructor is responsible for initializing the tracker for bundles providing automation resources and their
     * providers.
     *
     * @param bc is the execution context of the bundle being started, serves for creation of the tracker.
     * @param tProvider is a reference to an implementation of {@link TemplateProvider}.
     * @param mProvider is a reference to an implementation of {@link ModuleTypeProvider}.
     * @param rImporter is a reference to an importer for {@link Rule}s.
     */
    public AutomationResourceBundlesEventQueue(BundleContext bc, AbstractResourceBundleProvider<Template> tProvider,
            AbstractResourceBundleProvider<ModuleType> mProvider, AbstractResourceBundleProvider<Rule> rImporter) {
        this.tProvider = tProvider;
        this.mProvider = mProvider;
        this.rImporter = rImporter;
        bTracker = new BundleTracker<Object>(bc, ~Bundle.UNINSTALLED, this);
        pkgAdmin = bc.getService(bc.getServiceReference(PackageAdmin.class));
    }

    /**
     * This method serves to open the bundle tracker when all providers are ready for work.
     */
    public void open() {
        if (tProvider.isReady() && mProvider.isReady() && rImporter.isReady()) {
            bTracker.open();
        }
    }

    /**
     * When a new event for a bundle providing automation resources is received, this will causes a creation of a new
     * thread if there is no other created yet. If the thread already exists, then it will be notified for the event.
     * Starting the thread will cause the execution of this method in separate thread.
     * <p>
     * The general contract of this method <code>run</code> is invoking of the
     * {@link #processBundleChanged(BundleEvent)} method and executing it in separate thread.
     *
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        boolean waitForEvents = true;
        while (true) {
            List<BundleEvent> l_queue = null;
            synchronized (this) {
                if (closed) {
                    notifyAll();
                    return;
                }
                if (queue.isEmpty()) {
                    if (waitForEvents) {
                        try {
                            wait(180000);
                        } catch (Throwable t) {
                        }
                        waitForEvents = false;
                        continue;
                    }
                    running = false;
                    notifyAll();
                    return;
                }
                l_queue = queue;
                shared = true;
            }
            Iterator<BundleEvent> events = l_queue.iterator();
            while (events.hasNext()) {
                processBundleChanged(events.next());
            }
            synchronized (this) {
                if (shared) {
                    queue.clear();
                }
                shared = false;
                waitForEvents = true;
                notifyAll();
            }
        }
    }

    /**
     * This method is invoked when the bundle stops to close the bundle tracker and to stop the separate thread if still
     * running.
     */
    public void stop() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        bTracker.close();
    }

    /**
     * A bundle that provides automation resources is being added to the {@code BundleTracker}.
     *
     * <p>
     * This method is called before a bundle that provides automation resources is added to the {@code BundleTracker}.
     * This method returns the object to be tracked for the specified {@code Bundle}. The returned object is stored in
     * the {@code BundleTracker} and is available from the {@link BundleTracker#getObject(Bundle) getObject} method.
     *
     * @param bundle The {@code Bundle} being added to the {@code BundleTracker} .
     * @param event The bundle event which caused this customizer method to be
     *            called or {@code null} if there is no bundle event associated with
     *            the call to this method.
     * @return The object to be tracked for the specified {@code Bundle} object
     *         or {@code null} if the specified {@code Bundle} object should not
     *         be tracked.
     */
    @Override
    public Object addingBundle(Bundle bundle, BundleEvent event) {
        if (isAnAutomationProvider(bundle)) {
            if (isFragmentBundle(bundle)) {
                List<Bundle> hosts = returnHostBundles(bundle);
                if (needToProcessFragment(bundle, hosts)) {
                    addEvent(bundle, event);
                    fillHostFragmentMapping(hosts);
                }
            } else {
                addEvent(bundle, event);
                fillHostFragmentMapping(bundle);
            }
            return bundle;
        }
        return null;
    }

    /**
     * A bundle tracked by the {@code BundleTracker} has been modified.
     *
     * <p>
     * This method is called when a bundle being tracked by the {@code BundleTracker} has had its state modified.
     *
     * @param bundle The {@code Bundle} whose state has been modified.
     * @param event The bundle event which caused this customizer method to be
     *            called or {@code null} if there is no bundle event associated with
     *            the call to this method.
     * @param object The tracked object for the specified bundle.
     */
    @Override
    public void modifiedBundle(Bundle bundle, BundleEvent event, Object object) {
        int type = event.getType();
        if (type == BundleEvent.UPDATED || type == BundleEvent.RESOLVED) {
            addEvent(bundle, event);
        }
    }

    /**
     * A bundle tracked by the {@code BundleTracker} has been removed.
     *
     * <p>
     * This method is called after a bundle is no longer being tracked by the {@code BundleTracker}.
     *
     * @param bundle The {@code Bundle} that has been removed.
     * @param event The bundle event which caused this customizer method to be
     *            called or {@code null} if there is no bundle event associated with
     *            the call to this method.
     * @param object The tracked object for the specified bundle.
     */
    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, Object object) {
        if (mProvider.isProviderProcessed(bundle) || tProvider.isProviderProcessed(bundle)
                || rImporter.isProviderProcessed(bundle)) {
            addEvent(bundle, event);
        }
        if (isFragmentBundle(bundle)) {
            for (Entry<Bundle, List<Bundle>> entry : hostFragmentMapping.entrySet()) {
                if (entry.getValue().contains(bundle)) {
                    Bundle host = entry.getKey();
                    addEvent(host, new BundleEvent(BundleEvent.UPDATED, host));
                }
            }
        }
    }

    /**
     * This method is used to check if the specified {@code Bundle} contains resource files providing automation
     * resources.
     *
     * @param bundle is a {@link Bundle} object to check.
     * @return <tt>true</tt> if the specified {@link Bundle} contains resource files providing automation
     *         resources, <tt>false</tt> otherwise.
     */
    private boolean isAnAutomationProvider(Bundle bundle) {
        return bundle.findEntries(AbstractResourceBundleProvider.PATH, null, false) != null;
    }

    /**
     * This method is used to get the host bundles of the parameter which is a fragment bundle.
     *
     * @param bundle an OSGi fragment bundle.
     * @return a list with the hosts of the <code>fragment</code> parameter.
     */
    private List<Bundle> returnHostBundles(Bundle fragment) {
        List<Bundle> hosts = new ArrayList<Bundle>();
        // R5 version of the code --->>>
        // BundleWiring wiring = fragment.adapt(BundleWiring.class);
        // if (wiring == null) {
        // for (Entry<Bundle, List<Bundle>> entry : hostFragmentMapping.entrySet()) {
        // if (entry.getValue().contains(fragment)) {
        // Bundle host = entry.getKey();
        // hosts.add(host);
        // }
        // }
        // } else {
        // List<BundleWire> wires = wiring.getRequiredWires(HostNamespace.HOST_NAMESPACE);
        // if (wires != null && !wires.isEmpty()) {
        // for (BundleWire wire : wires) {
        // hosts.add(wire.getProvider().getBundle());
        // }
        // }
        // }

        // R4 version of the code --->>>
        Bundle[] bundles = pkgAdmin.getHosts(fragment);
        if (bundles != null) {
            for (int i = 0; i < bundles.length; i++) {
                hosts.add(bundles[i]);
            }
        }
        return hosts;
    }

    private void fillHostFragmentMapping(Bundle host) {
        List<Bundle> fragments = new ArrayList<Bundle>();
        // R5 version of the code --->>>
        // BundleWiring wiring = host.adapt(BundleWiring.class);
        // List<BundleWire> wires = wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE);
        // if (wires == null || wires.isEmpty()) {
        // return;
        // }
        // for (BundleWire wire : wires) {
        // fragments.add(wire.getRequirer().getBundle());
        // }

        // R4 version of the code --->>>
        Bundle[] bundles = pkgAdmin.getFragments(host);
        if (bundles != null) {
            for (int i = 0; i < bundles.length; i++) {
                fragments.add(bundles[i]);
            }
        }
        synchronized (hostFragmentMapping) {
            hostFragmentMapping.put(host, fragments);
        }
    }

    private void fillHostFragmentMapping(List<Bundle> hosts) {
        for (Bundle host : hosts) {
            fillHostFragmentMapping(host);
        }
    }

    private boolean needToProcessFragment(Bundle fragment, List<Bundle> hosts) {
        if (hosts.isEmpty()) {
            return false;
        }
        synchronized (hostFragmentMapping) {
            for (Bundle host : hosts) {
                List<Bundle> fragments = hostFragmentMapping.get(host);
                if (fragments != null && fragments.contains(fragment)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isFragmentBundle(Bundle bundle) {
        Dictionary<String, String> h = bundle.getHeaders();
        return h.get("Fragment-Host") != null;
    }

    /**
     * This method is called when a new event for a bundle providing automation resources is received. It causes a
     * creation of a new thread if there is no other created yet and starting the thread. If the thread already exists,
     * it is waiting for events and will be notified for the event.
     *
     * @param bundle
     *
     * @param event for a bundle tracked by the {@code BundleTracker}. It has been for adding, modifying or removing the
     *            bundle.
     */
    private synchronized void addEvent(Bundle bundle, BundleEvent event) {
        if (closed) {
            return;
        }
        if (shared) {
            queue = new ArrayList<BundleEvent>();
            shared = false;
        }
        if (event == null) {
            event = initializeEvent(bundle);
        }
        if (queue.add(event)) {
            logger.debug("Process bundle event {}, for automation bundle '{}' ", event.getType(),
                    event.getBundle().getSymbolicName());
            if (running) {
                notifyAll();
            } else {
                Thread th = new Thread(this, "Automation Provider Processing Queue");
                th.start();
                running = true;
            }
        }
    }

    private BundleEvent initializeEvent(Bundle bundle) {
        switch (bundle.getState()) {
            case Bundle.INSTALLED:
                return new BundleEvent(BundleEvent.INSTALLED, bundle);
            case Bundle.RESOLVED:
                return new BundleEvent(BundleEvent.RESOLVED, bundle);
            default:
                return new BundleEvent(BundleEvent.STARTED, bundle);
        }
    }

    /**
     * Depending on the action committed against the bundle supplier of automation resources, this method performs the
     * appropriate actions - calls for the each provider:
     * <ul>
     * <li>{@link AbstractResourceBundleProvider#processAutomationProviderUninstalled(Bundle)} method,
     * <li>{@link AbstractResourceBundleProvider#processAutomationProvider(Bundle)} method
     * <li>or both in this order.
     * </ul>
     *
     * @param event for a bundle tracked by the {@code BundleTracker}. It has been for adding, modifying or removing the
     *            bundle.
     */
    private void processBundleChanged(BundleEvent event) {
        Bundle bundle = event.getBundle();
        if (isFragmentBundle(bundle)) {
            processFragmentBundleUpdated(returnHostBundles(bundle));
        } else {
            switch (event.getType()) {
                case BundleEvent.UPDATED:
                    processBundleUpdated(bundle);
                    break;
                case BundleEvent.UNINSTALLED:
                    processBundleUninstalled(bundle);
                    break;
                default:
                    processBundleDefault(bundle);
            }
        }
    }

    private void processFragmentBundleUpdated(List<Bundle> hosts) {
        for (Bundle host : hosts) {
            processBundleUpdated(host);
        }
    }

    private void processBundleUpdated(Bundle bundle) {
        if (mProvider.isProviderProcessed(bundle)) {
            mProvider.processAutomationProviderUninstalled(bundle);
        }
        mProvider.processAutomationProvider(bundle);
        if (tProvider.isProviderProcessed(bundle)) {
            tProvider.processAutomationProviderUninstalled(bundle);
        }
        tProvider.processAutomationProvider(bundle);
        if (rImporter.isProviderProcessed(bundle)) {
            rImporter.processAutomationProviderUninstalled(bundle);
        }
        rImporter.processAutomationProvider(bundle);
    }

    private void processBundleUninstalled(Bundle bundle) {
        if (mProvider.isProviderProcessed(bundle)) {
            mProvider.processAutomationProviderUninstalled(bundle);
        }
        if (tProvider.isProviderProcessed(bundle)) {
            tProvider.processAutomationProviderUninstalled(bundle);
        }
        if (rImporter.isProviderProcessed(bundle)) {
            rImporter.processAutomationProviderUninstalled(bundle);
        }
        hostFragmentMapping.remove(bundle);
    }

    private void processBundleDefault(Bundle bundle) {
        if (!mProvider.isProviderProcessed(bundle)) {
            mProvider.processAutomationProvider(bundle);
        }
        if (!tProvider.isProviderProcessed(bundle)) {
            tProvider.processAutomationProvider(bundle);
        }
        if (!rImporter.isProviderProcessed(bundle)) {
            rImporter.processAutomationProvider(bundle);
        }
    }
}
