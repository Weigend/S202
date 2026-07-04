package de.weigend.s202.ui.city3d;

import java.nio.file.Path;

/**
 * Dev smoke test for the {@link CityView3DServer} selection channel (no UI):
 * starts the server, prints browser→app selections, and pushes an app→browser selection
 * every few seconds so an SSE client ({@code curl -N .../events}) can observe it.
 *
 * <pre>
 * mvn -q -o org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=de.weigend.s202.ui.city3d.CitySyncSmoke -Dexec.args="../city3d/dist"
 * </pre>
 */
public final class CitySyncSmoke {

    public static void main(String[] args) throws Exception {
        Path dist = Path.of(args.length > 0 ? args[0] : "../city3d/dist");
        CityView3DServer server = CityView3DServer.getOrStart(dist);
        server.setSelectionListener(fqn -> System.out.println("APP GOT (browser->app): " + fqn));
        System.out.println("URL: " + server.url());

        String[] fqns = {"com.example.A", "com.example", "sccs.core", null};
        int i = 0;
        while (true) {
            String fqn = fqns[i++ % fqns.length];
            System.out.println("PUSH (app->browser): " + fqn);
            server.pushSelection(fqn);
            Thread.sleep(3000);
        }
    }
}
