package com.linklab.whiteboardsnap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL;
import static java.nio.charset.StandardCharsets.UTF_8;

public class MainActivity extends AppCompatActivity implements OnRobotReadyListener {
    private Robot robot;
    private Spinner whiteboardSpinner, cameraSpinner;
    private Button goButton, exitButton;
    private ImageView imageView;
    private EditText angleInput;
    static final String TAG = "WhiteBoard";

    String selectedLocation = "";
    String initialLocationName = "starting position";
    boolean receivingImages = false;

    private static final String subscriptionTopic = "temi-data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        robot = Robot.getInstance();

        whiteboardSpinner = findViewById(R.id.spinner);
        cameraSpinner = findViewById(R.id.spinner_camera);
        goButton = findViewById(R.id.go_button);
        exitButton = findViewById(R.id.exit_button);
        imageView = findViewById(R.id.imageView);
        angleInput = findViewById(R.id.editTextAngle);

        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finishAndRemoveTask();
            }
        });

        // set options for selecting camera
        List<String> cameraOptions = new ArrayList<>();
        cameraOptions.add("Regular Lens");
        cameraOptions.add("Wide Angle Lens");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, cameraOptions);

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(adapter);
        cameraSpinner.setSelection(1); // default to wide angle

        checkPermissionsOrRequest();

        // listen for broadcast messages sent out by Camera2Service when captured images are ready
        IntentFilter filter = new IntentFilter();
        filter.addAction("imageReady");

        BroadcastReceiver updateUIReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context,
                                  Intent intent) {
                // use this flag to ensure we don't receive repeated broadcasts
                if(receivingImages) {
                    receivingImages = false;
                    Log.d(TAG, "onReceive: received broadcast from Camera2Service. Updating UI");
                    File imageFile = new File(Environment.getExternalStorageDirectory() + "/Pictures/image.jpg");

                    // update imageView to get a preview of the clicked picture for debugging
                    updateImageView(imageFile);
                    Log.d(TAG, "onReceive: updated imageView");

                    Log.d(TAG, "onReceive: about to send to slack");
                    sendFileOnSlack(imageFile);

                    // move temi to its initial location
                    robot.goTo(initialLocationName);
                }
            }
        };

        registerReceiver(updateUIReceiver, filter);
        waitForMqttMessages();
    }

    private void updateImageView(File imageFile) {
        Bitmap myBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        imageView.setImageBitmap(myBitmap);
    }

    private void sendFileOnSlack(File file) {
        String serverURL = "https://slack.com/api/files.upload";
        String userToken = BuildConfig.SLACK_USER_TOKEN;
        try {
            OkHttpClient client = new OkHttpClient();

            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(),
                            RequestBody.create(MediaType.parse("image/jpg"), file))
                    .addFormDataPart("initial_comment", String.format("Here's the snap of %s you asked for!", selectedLocation))
                    .addFormDataPart("channels", "whiteboard-messages")
                    .build();

            Request request = new Request.Builder()
                    .url(serverURL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + userToken)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(final Call call, final IOException e) {
                    // Handle the error
                    Log.d(TAG, "onFailure: http post failed");
                    e.printStackTrace();
                }

                @Override
                public void onResponse(final Call call, final Response response) {
                    if (!response.isSuccessful()) {
                        // Handle the error
                        Log.d(TAG, "onResponse: not successful");
                        Log.d(TAG, "onResponse: " + response);
                    }
                    // Upload successful
                    Log.d(TAG, "onResponse: success!");
                    Log.d(TAG, "onResponse: " + response);
                }
            });
        } catch (Exception ex) {
            // Handle the error
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        robot.addOnRobotReadyListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        robot.removeOnRobotReadyListener(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onRobotReady(boolean isReady) {
        // get the list of locations from the robot and populate the spinner
        List<String> locations = robot.getLocations();
        List<String> whiteboardLocs = new ArrayList<>();
        for (String location : locations) {
            if (location.startsWith("whiteboard")) {
                whiteboardLocs.add(location);
            }
        }
        Collections.sort(whiteboardLocs);
        Log.v(TAG, "locations = " + whiteboardLocs);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, whiteboardLocs);

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        whiteboardSpinner.setAdapter(adapter);

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "user pressed go button");

                selectedLocation = whiteboardSpinner.getSelectedItem().toString();
                int angle = Integer.parseInt(angleInput.getText().toString());
                int cameraId = cameraSpinner.getSelectedItemPosition();
                moveAndClickPicture(selectedLocation, angle, cameraId);
            }
        });
    }

    // cameraId: regular lens = 0, wide angle = 1
    private void moveAndClickPicture(String location, int headAngle, int cameraId) {
        // ensures that the broadcast message sent from the camera service is received
        receivingImages = true;

        // store current location so that we can go back to where we were
        robot.saveLocation(initialLocationName);
        Log.d(TAG, "moveAndClickPicture: temporarily saved the current location");

        // setup a location change listener to obtain events when temi is moving
        robot.addOnGoToLocationStatusChangedListener((currentLoc, status, descriptionId, description) -> {
            Log.d(TAG, String.format("onGoToLocationStatusChanged: location: %s, status: %s, desc: %s", currentLoc, status, description));

            // if we've reached, adjust head angle and click picture
            if (currentLoc.equals(location) && status.equals("complete")) {
                Log.d(TAG, "reached destination. now adjusting head angle.");
                robot.tiltAngle(headAngle);
                Log.d(TAG, "now clicking picture.");
                clickPicture(cameraId); // the camera service sends a broadcast once done
            } else if(currentLoc.equals(initialLocationName) && status.equals("complete")) {
                // when we've reached back to original position, remove the temporarily saved initial location
                Log.d(TAG, "reached original position");
                robot.deleteLocation(initialLocationName);
                Log.d(TAG, "deleted temporary location");
            }
        });
        // ask temi to go to the requested location
        robot.goTo(location);
    }

    private void checkPermissionsOrRequest() {
        // The request code used in ActivityCompat.requestPermissions()
        // and returned in the Activity's onRequestPermissionsResult()
        int PERMISSION_ALL = 1;
        String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.ACCESS_NETWORK_STATE
        };

        if (!hasPermissions(this, permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_ALL);
        }
    }

    public boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "hasPermissions: no permission for " + permission);
                    return false;
                } else {
                    Log.d(TAG, "hasPermissions: YES permission for " + permission);
                }
            }
        }
        return true;
    }

    private void clickPicture(int cameraId) {
        Intent cameraServiceIntent = new Intent(MainActivity.this, Camera2Service.class);

        // camera apis expect the cameraId to be a string
        // from testing, regular lens = 0, wide angle = 1
        String idString = Integer.toString(cameraId);
        cameraServiceIntent.putExtra("cameraId", idString);
        startService(cameraServiceIntent);
    }

    @SuppressLint("NewApi")
    private void waitForMqttMessages() {
        final String host = "c51239432e1144d5864bbfca54347978.s1.eu.hivemq.cloud";
        final String username = BuildConfig.HIVEMQ_USER;
        final String password = BuildConfig.HIVEMQ_PASSWORD;

        //create an MQTT client
        final Mqtt5BlockingClient client = MqttClient.builder()
                .useMqttVersion5()
                .serverHost(host)
                .serverPort(8883)
                .sslWithDefaultConfig()
                .buildBlocking();

        //connect to HiveMQ Cloud with TLS and username/pw
        client.connectWith()
                .simpleAuth()
                .username(username)
                .password(UTF_8.encode(password))
                .applySimpleAuth()
                .send();

        Log.d(TAG, "waitForMqttMessages: Connected successfully");

        //subscribe to the topic "my/test/topic"
        client.subscribeWith()
                .topicFilter(subscriptionTopic)
                .send();

        // set a callback that is called when a message is received (using the async API style)
        client.toAsync().publishes(ALL, publish -> {
            String payload = UTF_8.decode(publish.getPayload().get()).toString();
            Log.d(TAG, "waitForMqttMessages: Received message: " + payload);

            try {
                JSONObject sensorData = new JSONObject(payload);
                if(sensorData.has("temi_request")) {
                    selectedLocation = sensorData.getString("location");

                    // we pick the location, and default to the wideangle lens with a head angle of 18
                    moveAndClickPicture(selectedLocation, 18, 1);
                } else {
                    Log.d(TAG, "waitForMqttMessages: received invalid request!");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            // disconnect the client after a message was received
            // client.disconnect();
        });
    }

//    @SuppressLint("NewApi")
//    private void useHiveMqLocal() {
//        final String host = "172.27.154.209";
//
//        //create an MQTT client
//        final Mqtt3BlockingClient client = MqttClient.builder()
//                .useMqttVersion3()
//                .serverHost(host)
//                .serverPort(1883)
//                .buildBlocking();
//
//        //connect to HiveMQ Cloud with TLS and username/pw
//        client.connectWith()
//                .send();
//
//        System.out.println("Connected successfully");
//
//        //subscribe to the topic "my/test/topic"
//        client.subscribeWith()
//                .topicFilter("temi-data")
//                .send();
//
//        // set a callback that is called when a message is received (using the async API style)
//        client.toAsync().publishes(ALL, publish -> {
//            String payload = UTF_8.decode(publish.getPayload().get()).toString();
//
//            if(payload.equals("invoke")) {
//                Log.d(TAG, "messageArrived: Received message for invoke");
//                robot.goTo("whiteboard 225");
//            }
//            //disconnect the client after a message was received
////            client.disconnect();
//        });
//    }
}