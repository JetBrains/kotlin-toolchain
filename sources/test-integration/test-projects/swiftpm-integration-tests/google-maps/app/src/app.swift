import GoogleMaps
import KotlinModules

@main
struct iOSApp {
    static func main() {
        GMSServices.provideAPIKey("APIKEY")
        GMSMapView.map(withFrame: .zero, camera: KotlinApp().callLib())
    }
}