//
//  UnityPlayerUtils.swift
//  flutter_unity_widget
//
//  Created by Rex Raphael on 30/01/2021.
//

import Foundation
import UnityFramework

private var unity_warmed_up = false
// Hack to work around iOS SDK 4.3 linker problem
// we need at least one __TEXT, __const section entry in main application .o files
// to get this section emitted at right time and so avoid LC_ENCRYPTION_INFO size miscalculation
private let constsection = 0

// keep arg for unity init from non main
var gArgc: Int32 = 0
var gArgv: UnsafeMutablePointer<UnsafeMutablePointer<Int8>?>? = nil
var appLaunchOpts: [UIApplication.LaunchOptionsKey: Any]? = [:]
var gMachHeader: UnsafeMutablePointer<MachHeader>? = nil

/***********************************PLUGIN_ENTRY STARTS**************************************/
public func InitUnityIntegration(argc: Int32, argv: UnsafeMutablePointer<UnsafeMutablePointer<Int8>?>?) {
    gArgc = argc
    gArgv = argv
}

public func InitUnityIntegrationWithOptions(
    argc: Int32,
    argv: UnsafeMutablePointer<UnsafeMutablePointer<Int8>?>?,
    machHeader: UnsafeMutablePointer<MachHeader>,
    _ launchingOptions:  [UIApplication.LaunchOptionsKey: Any]?) {
    gArgc = argc
    gArgv = argv
    appLaunchOpts = launchingOptions
}
/***********************************PLUGIN_ENTRY END**************************************/

// Load unity framework for fisrt run
func UnityFrameworkLoad() -> UnityFramework? {
    var bundlePath: String? = nil
    bundlePath = Bundle.main.bundlePath
    bundlePath = (bundlePath ?? "") + "/Frameworks/UnityFramework.framework"

    let bundle = Bundle(path: bundlePath ?? "")
    if bundle?.isLoaded == false {
        bundle?.load()
    }
    let ufw = bundle?.principalClass?.getInstance()
    if (ufw?.appController() == nil) {
        ufw!.setExecuteHeader(gMachHeader)
    }
    return ufw
}

/*********************************** GLOBAL FUNCS & VARS START**************************************/

public var globalChannel: FlutterMethodChannel? = nil
private var unityPlayerUtils: UnityPlayerUtils? = nil
func GetUnityPlayerUtils() -> UnityPlayerUtils? {
    
    if unityPlayerUtils == nil {
        unityPlayerUtils = UnityPlayerUtils()
    }
    return unityPlayerUtils
}

/*********************************** GLOBAL FUNCS & VARS END****************************************/

var controller: UnityAppController?
var sharedApplication: UIApplication?

@objc protocol UnityEventListener: AnyObject {

    func onReceiveMessage(_ message: UnsafePointer<Int8>?)
  
}

@objc public class UnityPlayerUtils: UIResponder, UIApplicationDelegate, UnityFrameworkListener {
    var ufw: UnityFramework!
    private var _isUnityPaused = false
    private var _isUnityReady = false
    private var _isUnityLoaded = false
    
    func initUnity() {
        if (self.unityIsInitiallized()) {
            self.ufw?.showUnityWindow()
            return
        }
        
        self.ufw = UnityFrameworkLoad()

        self.ufw?.setDataBundleId("com.unity3d.framework")

        registerUnityListener()
        self.ufw?.runEmbedded(withArgc: gArgc, argv: gArgv, appLaunchOpts: appLaunchOpts)

        if self.ufw?.appController() != nil {
            controller = self.ufw?.appController()
            self.ufw?.appController()?.window?.windowLevel = UIWindow.Level(UIWindow.Level.normal.rawValue - 1)
        }
        _isUnityLoaded = true
    }

    // check if unity is initiallized
    func unityIsInitiallized() -> Bool {
        if self.ufw != nil {
            return true
        }
        
        return false
    }

    // Create new unity player
    func createPlayer(completed: @escaping (_ view: UIView?) -> Void) {
        if self.unityIsInitiallized() && self._isUnityReady {
            completed(controller?.rootView)
        }
        
        NotificationCenter.default.addObserver(forName: NSNotification.Name("UnityReady"), object: nil, queue: OperationQueue.main, using: { note in
            self._isUnityReady = true
            completed(controller?.rootView)
        })
        
        DispatchQueue.main.async {
            if (sharedApplication == nil) {
                sharedApplication = UIApplication.shared
            }

            // Always keep Flutter window on top
            let flutterUIWindow = sharedApplication?.keyWindow
            flutterUIWindow?.windowLevel = UIWindow.Level(UIWindow.Level.normal.rawValue + 1) // Always keep Flutter window in top
            sharedApplication?.keyWindow?.windowLevel = UIWindow.Level(UIWindow.Level.normal.rawValue + 1)
            
            self.initUnity()
            unity_warmed_up = true
            self.listenAppState()
        }
        
        if unity_warmed_up == true {
            self._isUnityReady = true
            self._isUnityLoaded = true
            completed(controller?.rootView)
        }
    }

    func registerUnityListener() {
        if self.unityIsInitiallized() {
            self.ufw?.register(self)
        }
    }

    func unregisterUnityListener() {
        if self.unityIsInitiallized() {
            self.ufw?.unregisterFrameworkListener(self)
        }
    }

    @objc
    public func unityDidUnload(_ notification: Notification!) {
        unregisterUnityListener()
        self.ufw = nil
        self._isUnityReady = false
        self._isUnityLoaded = false
    }
    
    @objc func handleAppStateDidChange(notification: Notification?) {
        if !self._isUnityReady {
            return
        }

        let unityAppController = self.ufw?.appController as? UnityAppController
        let application = UIApplication.shared

        if notification?.name == UIApplication.willResignActiveNotification {
            unityAppController?.applicationWillResignActive(application)
        } else if notification?.name == UIApplication.didEnterBackgroundNotification {
            unityAppController?.applicationDidEnterBackground(application)
        } else if notification?.name == UIApplication.willEnterForegroundNotification {
            unityAppController?.applicationWillEnterForeground(application)
        } else if notification?.name == UIApplication.didBecomeActiveNotification {
            unityAppController?.applicationDidBecomeActive(application)
        } else if notification?.name == UIApplication.willTerminateNotification {
            unityAppController?.applicationWillTerminate(application)
        } else if notification?.name == UIApplication.didReceiveMemoryWarningNotification {
            unityAppController?.applicationDidReceiveMemoryWarning(application)
        }
    }
    
    
    // Listener for app lifecycle eventa
    func listenAppState() {
        for name in [
            UIApplication.didBecomeActiveNotification,
            UIApplication.didEnterBackgroundNotification,
            UIApplication.willTerminateNotification,
            UIApplication.willResignActiveNotification,
            UIApplication.willEnterForegroundNotification,
            UIApplication.didReceiveMemoryWarningNotification
        ] {
            guard let name = name as? String else {
                continue
            }
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(self.handleAppStateDidChange),
                name: NSNotification.Name(name),
                object: nil)
        }
    }
    // Pause unity player
    func pause() {
        self.ufw?.pause(true)
        self._isUnityPaused = true
    }

    // Resume unity player
    func resume() {
        self.ufw?.pause(false)
        self._isUnityPaused = false
    }

    // Unoad unity player
    func unload() {
        self.ufw?.unloadApplication()
    }

    func isUnityLoaded() -> Bool {
        return _isUnityLoaded
    }

    func isUnityPaused() -> Bool {
        return _isUnityPaused
    }

    // Quit unity player application
    func quit() {
        self.ufw?.quitApplication(0)
        self._isUnityLoaded = false
    }

    // Post message to unity
    func postMessageToUnity(gameObject: String?, unityMethodName: String?, unityMessage: String?) {
        if self.unityIsInitiallized() {
            self.ufw?.sendMessageToGO(withName: gameObject, functionName: unityMethodName, message: unityMessage)
        }
    }
    
    @objc
    public static func unityMessageHandler(_ message: UnsafePointer<Int8>?) {
        if let strMsg = message {
            globalChannel?.invokeMethod("events#onUnityMessage", arguments: String(utf8String: strMsg))
        } else {
            globalChannel?.invokeMethod("events#onUnityMessage", arguments: "")
        }
    }

    @objc
    public static func unitySceneLoadedHandler(name: UnsafePointer<Int8>?, buildIndex: UnsafePointer<Int>?, isLoaded: UnsafePointer<ObjCBool>?, isValid: UnsafePointer<ObjCBool>?) {
        if let sceneName = name,
           let bIndex = buildIndex,
           let loaded = isLoaded,
           let valid = isValid {
        
            let addObject: Dictionary<String, Any> = [
                "name": String(utf8String: sceneName) ?? "",
                "buildIndex": bIndex,
                "isLoaded": loaded,
                "isValid": valid,
            ]
            globalChannel?.invokeMethod("events#onUnitySceneLoaded", arguments: addObject)
        }
    }
}
