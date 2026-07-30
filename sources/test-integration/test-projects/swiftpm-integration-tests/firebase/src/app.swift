import FirebaseCore
import FirebaseFirestore
import KotlinModules

@main
struct iOSApp {
    static func main() {
        let opts = FirebaseOptions(googleAppID: "1:1234567890:ios:abcdef123456", gcmSenderID: "1234567890")
        opts.apiKey = "AIzaSyDrandomKeyGeneratedForDebug001234"
        opts.projectID = "dummy"
        FirebaseApp.configure(options: opts)

        CallFirebase().createFirestore().collection("users").document("local_user")
            .setData(["name": "John Doe", "isOffline": true])
        print("Returning from Swift")
    }
}