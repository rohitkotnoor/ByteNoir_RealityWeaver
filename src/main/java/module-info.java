module com.realityweaver {
    requires java.desktop;
    requires java.net.http;
    requires javafx.controls;
    requires java.prefs;
    requires com.google.gson;

    opens com.realityweaver.wallpaper to com.google.gson;
    exports com.realityweaver;
}
