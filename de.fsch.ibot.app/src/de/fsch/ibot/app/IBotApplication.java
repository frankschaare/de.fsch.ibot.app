package de.fsch.ibot.app;

import java.util.List;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.e4.core.contexts.ContextFunction;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.contexts.RunAndTrack;
import org.eclipse.e4.core.internal.services.EclipseAdapter;
import org.eclipse.e4.core.services.adapter.Adapter;
import org.eclipse.e4.core.services.contributions.IContributionFactory;
import org.eclipse.e4.core.services.log.ILoggerProvider;
import org.eclipse.e4.core.services.log.Logger;
import org.eclipse.e4.ui.internal.workbench.ActiveChildLookupFunction;
import org.eclipse.e4.ui.internal.workbench.ActivePartLookupFunction;
import org.eclipse.e4.ui.internal.workbench.DefaultLoggerProvider;
import org.eclipse.e4.ui.internal.workbench.E4Workbench;
import org.eclipse.e4.ui.internal.workbench.ExceptionHandler;
import org.eclipse.e4.ui.internal.workbench.ModelServiceImpl;
import org.eclipse.e4.ui.internal.workbench.PlaceholderResolver;
import org.eclipse.e4.ui.internal.workbench.ReflectionContributionFactory;
import org.eclipse.e4.ui.internal.workbench.ResourceHandler;
import org.eclipse.e4.ui.internal.workbench.SelectionAggregator;
import org.eclipse.e4.ui.internal.workbench.SelectionServiceImpl;
import org.eclipse.e4.ui.internal.workbench.WorkbenchLogger;
import org.eclipse.e4.ui.model.application.MAddon;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.basic.impl.BasicPackageImpl;
import org.eclipse.e4.ui.model.application.ui.impl.UiPackageImpl;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.e4.ui.workbench.IExceptionHandler;
import org.eclipse.e4.ui.workbench.IModelResourceHandler;
import org.eclipse.e4.ui.workbench.IWorkbench;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessRemovals;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPlaceholderResolver;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;
import org.w3c.dom.css.CSSStyleDeclaration;

@SuppressWarnings("restriction")
public class IBotApplication implements IApplication
{
public static final String METADATA_FOLDER = ".metadata"; //$NON-NLS-1$
private static final String VERSION_FILENAME = "version.ini"; //$NON-NLS-1$
private static final String WORKSPACE_VERSION_KEY = "org.eclipse.core.runtime"; //$NON-NLS-1$
private static final String WORKSPACE_VERSION_VALUE = "2"; //$NON-NLS-1$
private static final String APPLICATION_MODEL_PATH_DEFAULT = "Application.e4xmi";
private static final String CONTEXT_INITIALIZED = "org.eclipse.ui.contextInitialized";
public static final String THEME_ID = "cssTheme";
	
private String[] args;	
private Object lcManager;
private IModelResourceHandler handler;
private E4Workbench workbench = null;

	public IBotApplication()
	{
		// TODO Auto-generated constructor stub
	}

	@Override
	public Object start(IApplicationContext context) throws Exception
	{
	createE4Workbench(context);
	
	// launch(this.getClass());
	return null;
	}
	
	public E4Workbench createE4Workbench(IApplicationContext applicationContext) 
	{
	args = (String[]) applicationContext.getArguments().get(IApplicationContext.APPLICATION_ARGS);

	IEclipseContext appContext = createDefaultContext();
	
	// TODO SWT Kram durch JavaFX ersetzen
	/*
	 * SWT spezifisch 
	 * 
	appContext.set(Display.class, display);
	appContext.set(Realm.class, SWTObservables.getRealm(display));
	appContext.set(UISynchronize.class, new UISynchronize() 
		{
		public void syncExec(Runnable runnable) 
		{
		display.syncExec(runnable);
		}

		public void asyncExec(Runnable runnable) 
		{
		display.asyncExec(runnable);
		}
		});
	 */
	appContext.set(IApplicationContext.class, applicationContext);

		// Check if DS is running
		if (!appContext.containsKey("org.eclipse.e4.ui.workbench.modeling.EPartService")) 
		{
		throw new IllegalStateException("Core services not available. Please make sure that a declarative service implementation (such as the bundle 'org.eclipse.equinox.ds') is available!");
		}

	// Get the factory to create DI instances with
	IContributionFactory factory = (IContributionFactory) appContext.get(IContributionFactory.class.getName());

	// Install the life-cycle manager for this session if there's one defined
	String lifeCycleURI = getArgValue(IWorkbench.LIFE_CYCLE_URI_ARG, applicationContext, false);
		if (lifeCycleURI != null) 
		{
		lcManager = factory.create(lifeCycleURI, appContext);
			if (lcManager != null) 
			{
			// Let the manager manipulate the appContext if desired
			ContextInjectionFactory.invoke(lcManager, PostContextCreate.class, appContext, null);
			}
		}
	
	/*
	 * Hier wird das Applikationsmodel geladen !
	 */
	MApplication appModel = loadApplicationModel(applicationContext, appContext);
	appModel.setContext(appContext);

	/*
	 * RTL Mode bleibt erst einmal unberücksichtigt 
	boolean isRtl = ((Window.getDefaultOrientation() & SWT.RIGHT_TO_LEFT) != 0);
	appModel.getTransientData().put(E4Workbench.RTL_MODE, isRtl);
	 */

		// for compatibility layer: set the application in the OSGi service
		// context (see Workbench#getInstance())
		if (!E4Workbench.getServiceContext().containsKey(MApplication.class.getName())) 
		{
		// first one wins.
		E4Workbench.getServiceContext().set(MApplication.class.getName(), appModel);
		}

	// Set the app's context after adding itself
	appContext.set(MApplication.class.getName(), appModel);

	// This context will be used by the injector for its extended data suppliers
	ContextInjectionFactory.setDefault(appContext);

	// adds basic services to the contexts
	initializeServices(appModel);

		// let the life cycle manager add to the model
		if (lcManager != null) 
		{
		ContextInjectionFactory.invoke(lcManager, ProcessAdditions.class, appContext, null);
		ContextInjectionFactory.invoke(lcManager, ProcessRemovals.class, appContext, null);
		}

	// Create the addons
	IEclipseContext addonStaticContext = EclipseContextFactory.create();
		for (MAddon addon : appModel.getAddons()) 
		{
		addonStaticContext.set(MAddon.class, addon);
		Object obj = factory.create(addon.getContributionURI(), appContext, addonStaticContext);
		addon.setObject(obj);
		}

	// Parse out parameters from both the command line and/or the product
	// definition (if any) and put them in the context
	String xmiURI = getArgValue(IWorkbench.XMI_URI_ARG, applicationContext, false);
	appContext.set(IWorkbench.XMI_URI_ARG, xmiURI);

	String themeId = getArgValue(IBotApplication.THEME_ID, applicationContext, false);
	appContext.set(IBotApplication.THEME_ID, themeId);

	String cssURI = getArgValue(IWorkbench.CSS_URI_ARG, applicationContext, false);
		if (cssURI != null) 
		{
		appContext.set(IWorkbench.CSS_URI_ARG, cssURI);
		}

		// Temporary to support old property as well
		if (cssURI != null && !cssURI.startsWith("platform:")) 
		{
		System.err.println("Warning " + cssURI 	+ " changed its meaning it is used now to run without theme support");
		appContext.set(IBotApplication.THEME_ID, cssURI);
		}

	String cssResourcesURI = getArgValue(IWorkbench.CSS_RESOURCE_URI_ARG, applicationContext, false);
	appContext.set(IWorkbench.CSS_RESOURCE_URI_ARG, cssResourcesURI);
	appContext.set(E4Workbench.RENDERER_FACTORY_URI, getArgValue(E4Workbench.RENDERER_FACTORY_URI, applicationContext, false));

	// This is a default arg, if missing we use the default rendering engine
	String presentationURI = getArgValue(IWorkbench.PRESENTATION_URI_ARG, applicationContext, false);
		if (presentationURI == null) 
		{
		// TODO WICHTIG !!!
		// presentationURI = PartRenderingEngine.engineURI;
		}
	appContext.set(IWorkbench.PRESENTATION_URI_ARG, presentationURI);

	// Instantiate the Workbench (which is responsible for
	// 'running' the UI (if any)...
	return workbench = new E4Workbench(appModel, appContext);
	}
	
	static public void initializeApplicationServices(IEclipseContext appContext) 
	{
	final IEclipseContext theContext = appContext;
	
	// we add a special tracker to bring up current selection from
	// the active window to the application level
	appContext.runAndTrack(new RunAndTrack() 
		{
		public boolean changed(IEclipseContext context) 
		{
		IEclipseContext activeChildContext = context.getActiveChild();
			if (activeChildContext != null) 
			{
			Object selection = activeChildContext.get(IServiceConstants.ACTIVE_SELECTION);
			theContext.set(IServiceConstants.ACTIVE_SELECTION, selection);
			}
		return true;
			}
		});

	// we create a selection service handle on every node that we are asked
	// about as handle needs to know its context
	appContext.set(ESelectionService.class.getName(), new ContextFunction() 
		{
		public Object compute(IEclipseContext context, String contextKey) 
		{
		return ContextInjectionFactory.make(SelectionServiceImpl.class, context);
		}
		});
	}	
	
	static public void initializeWindowServices(MWindow childWindow) 
	{
	IEclipseContext windowContext = childWindow.getContext();
	initWindowContext(windowContext);
		
	// Mostly MWindow contexts are lazily created by renderers and is not set at this point.
	((EObject) childWindow).eAdapters().add(new AdapterImpl() 
		{
			public void notifyChanged(Notification notification) 
			{
				if (notification.getFeatureID(MWindow.class) != BasicPackageImpl.WINDOW__CONTEXT) return;
			IEclipseContext windowContext = (IEclipseContext) notification.getNewValue();
			initWindowContext(windowContext);
			}
		});
	}	
	
	static private void initWindowContext(IEclipseContext windowContext) 
	{
		if (windowContext == null) return;
	SelectionAggregator selectionAggregator = ContextInjectionFactory.make(SelectionAggregator.class, windowContext);
	windowContext.set(SelectionAggregator.class, selectionAggregator);
	}	
	
	/*
	 * Iniatialisieren der Services
	 */
	static public void initializeServices(MApplication appModel) 
	{
	IEclipseContext appContext = appModel.getContext();
		// make sure we only add trackers once
		if (appContext.containsKey(CONTEXT_INITIALIZED))
			return;
	appContext.set(CONTEXT_INITIALIZED, "true");
	
	initializeApplicationServices(appContext);
	
	List<MWindow> windows = appModel.getChildren();
		for (MWindow childWindow : windows) 
		{
		initializeWindowServices(childWindow);
		}

		((EObject) appModel).eAdapters().add(new AdapterImpl() {
			public void notifyChanged(Notification notification) {
				if (notification.getFeatureID(MApplication.class) != UiPackageImpl.ELEMENT_CONTAINER__CHILDREN)
					return;
				if (notification.getEventType() != Notification.ADD)
					return;
				MWindow childWindow = (MWindow) notification.getNewValue();
				initializeWindowServices(childWindow);
			}
		});
	}	
	
	/*
	 * Lädt das Applikationsmodel
	 */
	private MApplication loadApplicationModel(IApplicationContext appContext, IEclipseContext eclipseContext) 
	{
	MApplication theApp = null;

	String appModelPath = getArgValue(IWorkbench.XMI_URI_ARG, appContext, false);
		if (appModelPath == null || appModelPath.length() == 0) 
		{
		Bundle brandingBundle = appContext.getBrandingBundle();
			if (brandingBundle != null)
			{
			appModelPath = brandingBundle.getSymbolicName() + "/" + IBotApplication.APPLICATION_MODEL_PATH_DEFAULT;
			}
		}
		
	Assert.isNotNull(appModelPath, IWorkbench.XMI_URI_ARG + " argument missing");
	final URI initialWorkbenchDefinitionInstance = URI.createPlatformPluginURI(appModelPath, true);

	eclipseContext.set(E4Workbench.INITIAL_WORKBENCH_MODEL_URI, initialWorkbenchDefinitionInstance);

	// Save and restore
	boolean saveAndRestore;
	String value = getArgValue(IWorkbench.PERSIST_STATE, appContext, false);
	saveAndRestore = value == null || Boolean.parseBoolean(value);
	eclipseContext.set(IWorkbench.PERSIST_STATE, Boolean.valueOf(saveAndRestore));

	
		/*
		 * SWT spezifisch
		 * when -data @none or -data @noDefault options
		Location instanceLocation = WorkbenchSWTActivator.getDefault().getInstanceLocation();
		if (instanceLocation != null && instanceLocation.getURL() != null) {
			eclipseContext.set(E4Workbench.INSTANCE_LOCATION, instanceLocation);
		} else {
			eclipseContext.set(IWorkbench.PERSIST_STATE, false);
		}
		 */

		// Persisted state
	boolean clearPersistedState;
	value = getArgValue(IWorkbench.CLEAR_PERSISTED_STATE, appContext, true);
	clearPersistedState = value != null && Boolean.parseBoolean(value);
	eclipseContext.set(IWorkbench.CLEAR_PERSISTED_STATE, Boolean.valueOf(clearPersistedState));

	// Delta save and restore
	boolean deltaRestore;
	value = getArgValue(E4Workbench.DELTA_RESTORE, appContext, false);
	deltaRestore = value == null || Boolean.parseBoolean(value);
	eclipseContext.set(E4Workbench.DELTA_RESTORE, Boolean.valueOf(deltaRestore));

	String resourceHandler = getArgValue(IWorkbench.MODEL_RESOURCE_HANDLER, appContext, false);

		if (resourceHandler == null) 
		{
		resourceHandler = "bundleclass://org.eclipse.e4.ui.workbench/" + ResourceHandler.class.getName();
		}

	IContributionFactory factory = eclipseContext.get(IContributionFactory.class);
	handler = (IModelResourceHandler) factory.create(resourceHandler, eclipseContext);
	eclipseContext.set(IModelResourceHandler.class, handler);

	Resource resource = handler.loadMostRecentModel();
	theApp = (MApplication) resource.getContents().get(0);

	return theApp;
	}	
	
	
	/*
	 * Service Context
	 * Translation habe ich herausgenommen
	 */
	public static IEclipseContext createDefaultHeadlessContext() 
	{
	IEclipseContext serviceContext = E4Workbench.getServiceContext();

	IExtensionRegistry registry = RegistryFactory.getRegistry();
	ExceptionHandler exceptionHandler = new ExceptionHandler();
	ReflectionContributionFactory contributionFactory = new ReflectionContributionFactory(registry);
	
	serviceContext.set(IContributionFactory.class, contributionFactory);
	serviceContext.set(IExceptionHandler.class, exceptionHandler);
	serviceContext.set(IExtensionRegistry.class, registry);

	serviceContext.set(Adapter.class, ContextInjectionFactory.make(EclipseAdapter.class, serviceContext));

		// No default log provider available
		if (serviceContext.get(ILoggerProvider.class) == null) 
		{
		serviceContext.set(ILoggerProvider.class, ContextInjectionFactory.make(DefaultLoggerProvider.class, serviceContext));
		}

	return serviceContext;
	}
	
	
	public static IEclipseContext createDefaultContext() 
	{
	IEclipseContext serviceContext = createDefaultHeadlessContext();
	
	final IEclipseContext appContext = serviceContext.createChild("WorkbenchContext");
	appContext.set(Logger.class, ContextInjectionFactory.make(WorkbenchLogger.class, appContext));
	appContext.set(EModelService.class, new ModelServiceImpl(appContext));
	appContext.set(EPlaceholderResolver.class, new PlaceholderResolver());

	// setup for commands and handlers
	appContext.set(IServiceConstants.ACTIVE_PART, new ActivePartLookupFunction());
	appContext.set(IServiceConstants.ACTIVE_SHELL, new ActiveChildLookupFunction(IServiceConstants.ACTIVE_SHELL, E4Workbench.LOCAL_ACTIVE_SHELL));
	appContext.set(IStylingEngine.class, new IStylingEngine() 
		{
		public void setClassname(Object widget, String classname) 
		{
		}

		public void setId(Object widget, String id) 
		{
		}

		public void style(Object widget) 
		{
		}

		public CSSStyleDeclaration getStyle(Object widget) 
		{
		return null;
		}

		public void setClassnameAndId(Object widget, String classname, String id) 
		{
		}
		});

		return appContext;
	}
	
	private String getArgValue(String argName, IApplicationContext appContext, boolean singledCmdArgValue) 
	{
		// Is it in the arg list ?
		if (argName == null || argName.length() == 0)
			return null;

		if (singledCmdArgValue) 
		{
			for (int i = 0; i < args.length; i++) {
				if (("-" + argName).equals(args[i]))
					return "true";
			}
		} 
		else 
		{
			for (int i = 0; i < args.length; i++) 
			{
				if (("-" + argName).equals(args[i]) && i + 1 < args.length)
					return args[i + 1];
			}
		}

	final String brandingProperty = appContext.getBrandingProperty(argName);
	return brandingProperty == null ? System.getProperty(argName) : brandingProperty;
	}
	
	@Override
	public void stop()
	{
		// TODO Auto-generated method stub

	}
}
