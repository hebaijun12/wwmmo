package au.com.codeka.warworlds;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HTTP;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import au.com.codeka.BackgroundRunner;
import au.com.codeka.common.Log;
import au.com.codeka.warworlds.ctrl.TransparentWebView;
import au.com.codeka.warworlds.eventbus.EventHandler;
import au.com.codeka.warworlds.game.starfield.StarfieldActivity;
import au.com.codeka.warworlds.model.EmpireManager;
import au.com.codeka.warworlds.model.EmpireShieldManager;
import au.com.codeka.warworlds.model.MyEmpire;
import au.com.codeka.warworlds.model.ShieldManager;

/**
 * Main activity. Displays the message of the day and lets you select "Start Game", "Options", etc.
 */
public class WarWorldsActivity extends BaseActivity {
  private static final Log log = new Log("WarWorldsActivity");
  private Context context = this;
  private Button startGameButton;
  private TextView connectionStatus;
  private HelloWatcher helloWatcher;
  private TextView realmName;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    log.info("WarWorlds activity starting...");
    super.onCreate(savedInstanceState);

    setContentView(R.layout.welcome);
    Util.setup(context);

    View rootView = findViewById(android.R.id.content);
    ActivityBackgroundGenerator.setBackground(rootView);

    startGameButton = (Button) findViewById(R.id.start_game_btn);
    connectionStatus = (TextView) findViewById(R.id.connection_status);
    realmName = (TextView) findViewById(R.id.realm_name);
    final Button realmSelectButton = (Button) findViewById(R.id.realm_select_btn);
    final Button optionsButton = (Button) findViewById(R.id.options_btn);

    refreshWelcomeMessage();

    realmSelectButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        startActivity(new Intent(context, RealmSelectActivity.class));
      }
    });

    optionsButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        startActivity(new Intent(context, GlobalOptionsActivity.class));
      }
    });

    startGameButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        final Intent intent = new Intent(context, StarfieldActivity.class);
        startActivity(intent);
      }
    });

    findViewById(R.id.help_btn).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse("http://www.war-worlds.com/doc/getting-started"));
        startActivity(i);
      }
    });

    findViewById(R.id.website_btn).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse("http://www.war-worlds.com/"));
        startActivity(i);
      }
    });

    findViewById(R.id.reauth_btn).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        final Intent intent = new Intent(context, AccountsActivity.class);
        startActivity(intent);
      }
    });
  }

  @Override
  public void onResumeFragments() {
    super.onResumeFragments();
    SharedPreferences prefs = Util.getSharedPreferences();
    if (!prefs.getBoolean("WarmWelcome", false)) {
      // if we've never done the warm-welcome, do it now
      log.info("Starting Warm Welcome");
      startActivity(new Intent(this, WarmWelcomeActivity.class));
      return;
    }

    if (RealmContext.i.getCurrentRealm() == null) {
      log.info("No realm selected, switching to RealmSelectActivity");
      startActivity(new Intent(this, RealmSelectActivity.class));
      return;
    }

    startGameButton.setEnabled(false);
    realmName.setText(String.format(Locale.ENGLISH,
        "Realm: %s", RealmContext.i.getCurrentRealm().getDisplayName()));

    final TextView empireName = (TextView) findViewById(R.id.empire_name);
    final ImageView empireIcon = (ImageView) findViewById(R.id.empire_icon);
    empireName.setText("");
    empireIcon.setImageBitmap(null);

    helloWatcher = new HelloWatcher();
    ServerGreeter.addHelloWatcher(helloWatcher);

    ShieldManager.eventBus.register(eventHandler);

    ServerGreeter.waitForHello(this, new ServerGreeter.HelloCompleteHandler() {
      @Override
      public void onHelloComplete(boolean success, ServerGreeter.ServerGreeting greeting) {
        if (success) {
          // we'll display a bit of debugging info along with the 'connected' message
          long maxMemoryBytes = Runtime.getRuntime().maxMemory();
          int memoryClass = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryClass();

          DecimalFormat formatter = new DecimalFormat("#,##0");
          String msg = String.format(Locale.ENGLISH,
              "Connected\r\nMemory Class: %d - Max bytes: %s\r\nVersion: %s%s", memoryClass,
              formatter.format(maxMemoryBytes), Util.getVersion(),
              Util.isDebug() ? " (debug)" : " (rel)");
          connectionStatus.setText(msg);
          startGameButton.setEnabled(true);

          MyEmpire empire = EmpireManager.i.getEmpire();
          if (empire != null) {
            empireName.setText(empire.getDisplayName());
            empireIcon.setImageBitmap(EmpireShieldManager.i.getShield(context, empire));
          }
        }
      }
    });
  }

  private void refreshWelcomeMessage() {
    new BackgroundRunner<Document>() {
      @Override
      protected Document doInBackground() {
        String url = (String) Util.getProperties().get("welcome.rss");
        try {
          // we have to use the built-in one because our special version assume all requests go
          // to the game server...
          HttpClient httpClient = new DefaultHttpClient();
          HttpGet get = new HttpGet(url);
          get.addHeader(HTTP.USER_AGENT, "wwmmo/" + Util.getVersion());
          HttpResponse response = httpClient.execute(new HttpGet(url));
          if (response.getStatusLine().getStatusCode() == 200) {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setValidating(false);

            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            return builder.parse(response.getEntity().getContent());
          }
        } catch (Exception e) {
          log.error("Error fetching MOTD.", e);
        }

        return null;
      }

      @Override
      protected void onComplete(Document rss) {
        SimpleDateFormat inputFormat =
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        SimpleDateFormat outputFormat =
            new SimpleDateFormat("dd MMM yyyy h:mm a", Locale.US);

        StringBuilder motd = new StringBuilder();
        if (rss != null) {
          NodeList itemNodes = rss.getElementsByTagName("item");
          for (int i = 0; i < itemNodes.getLength(); i++) {
            Element itemElem = (Element) itemNodes.item(i);
            String title = itemElem.getElementsByTagName("title").item(0).getTextContent();
            String content = itemElem.getElementsByTagName("description").item(0).getTextContent();
            String pubDate = itemElem.getElementsByTagName("pubDate").item(0).getTextContent();
            String link = itemElem.getElementsByTagName("link").item(0).getTextContent();

            try {
              Date date = inputFormat.parse(pubDate);
              motd.append("<h1>");
              motd.append(outputFormat.format(date));
              motd.append("</h1>");
            } catch (ParseException e) {
              // Shouldn't ever happen.
            }

            motd.append("<h2>");
            motd.append(title);
            motd.append("</h2>");
            motd.append(content);
            motd.append("<div style=\"text-align: right; border-bottom: dashed 1px #fff; "
                + "padding-bottom: 4px;\">");
            motd.append("<a href=\"");
            motd.append(link);
            motd.append("\">");
            motd.append("View forum post");
            motd.append("</a></div>");
          }
        }

        TransparentWebView motdView = (TransparentWebView) findViewById(R.id.motd);
        motdView.loadHtml("html/skeleton.html", motd.toString());
      }
    }.execute();
  }

  @Override
  public void onPause() {
    super.onPause();
    ServerGreeter.removeHelloWatcher(helloWatcher);
    ShieldManager.eventBus.unregister(eventHandler);
  }

  private Object eventHandler = new Object() {
    @EventHandler
    public void onShieldUpdated(ShieldManager.ShieldUpdatedEvent event) {
      // if it's the same as our empire, we'll need to update the icon we're currently showing.
      MyEmpire empire = EmpireManager.i.getEmpire();
      if (event.id == Integer.parseInt(empire.getKey())) {
        ImageView empireIcon = (ImageView) findViewById(R.id.empire_icon);
        empireIcon.setImageBitmap(EmpireShieldManager.i.getShield(context, empire));
      }
    }
  };

  private class HelloWatcher implements ServerGreeter.HelloWatcher {
    private int numRetries = 0;

    @Override
    public void onRetry(final int retries) {
      numRetries = retries + 1;
      connectionStatus.setText(String.format("Retrying (#%d)...", numRetries));
    }

    @Override
    public void onAuthenticating() {
      if (numRetries > 0) {
        return;
      }
      connectionStatus.setText("Authenticating...");
    }

    @Override
    public void onConnecting() {
      if (numRetries > 0) {
        return;
      }
      connectionStatus.setText("Connecting...");
    }
  }
}
