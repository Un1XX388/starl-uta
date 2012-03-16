package edu.illinois.mitra.lightpaint.main;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import edu.illinois.mitra.starl.bluetooth.RobotMotion;
import edu.illinois.mitra.starl.comms.GPSReceiver;
import edu.illinois.mitra.starl.comms.RobotMessage;
import edu.illinois.mitra.starl.interfaces.Cancellable;
import edu.illinois.mitra.starl.interfaces.MessageListener;
import edu.illinois.mitra.starl.interfaces.MotionListener;
import edu.illinois.mitra.starl.objects.common;
import edu.illinois.mitra.starl.objects.globalVarHolder;

public class RobotsActivity extends Activity implements MessageListener, MotionListener {
	private static final String TAG = "RobotsActivity";
	private static final String ERR = "Critical Error";
	
	private boolean DISPLAY_MODE = true;
	
	private globalVarHolder gvh = null;
	private static String gps_host = "192.168.1.100";
	private static int gps_port = 4000;
	private boolean connected = false;
	private boolean launched = false;
	private Collection<Cancellable> created;
	
	// THESE NAMES AND ASSOCIATED IP ADDRESSES ARE CONSTANT FOR TESTING.
	//									ALICE				BOB					CARLOS				DIANA
	private static final String [] mac = {"00:0A:3A:2E:C9:CA","00:0A:3A:2E:C8:21","00:0A:3A:2E:CB:73","00:0A:3A:2E:CB:43"};
	private static final String [] participants = {"Alice", "Bob", "Carlos", "Diana"};
	private static final String [] ips = {"192.168.1.101", "192.168.1.102", "192.168.1.103", "192.168.1.104"};
	
	// GUI variables
	private TextView txtRunNumber;
	private TextView txtRobotName;
	private TextView txtDebug;
	private ProgressBar pbBluetooth;
	private CheckBox cbGPS;
	private CheckBox cbBluetooth;
	private CheckBox cbRunning;
	private CheckBox cbPaintMode;
	private ProgressBar pbBattery;
	
	private int bluetoothStatus = 0;
	
	// SharedPreferences variables
	SharedPreferences prefs;
	private static final String PREF_SELECTED_ROBOT = "SELECTED_ROBOT";
	private static final String PREF_SELECTED_PAINTMODE = "PAINT_MODE";
	private int selected_robot = 0;
	
	// Lightpainting specific
	private float overrideBrightness = 1;
	private int reqBrightness = 100; 
	private WindowManager.LayoutParams lp;	
	private View vi;
	private int defaultBrightness = -1;
	
    public static final int MESSAGE_SCREEN = 50;
    public static final int MESSAGE_SCREEN_COLOR = 51;
	
    private final Handler debug_handler = new Handler() {
    	public void handleMessage(Message msg) {	    	
	    	switch(msg.what) {
	    	case common.MESSAGE_TOAST:
	    		Toast.makeText(getApplicationContext(), msg.obj.toString(), Toast.LENGTH_LONG).show();
	    		break;
	    	case common.MESSAGE_LOCATION:
	    		cbGPS.setChecked((Integer)msg.obj == common.GPS_RECEIVING);
	    		break;
	    	case common.MESSAGE_BLUETOOTH:
	    		bluetoothStatus = (Integer)msg.obj;
	    		updateGUI();
	    		break;
	    	case common.MESSAGE_LAUNCH:
	    		launch((String) msg.obj);
	    		break;
	    	case common.MESSAGE_ABORT:
    			// Disconnect and reconnect
	    		abort();
	    		break;
	    	case common.MESSAGE_DEBUG:
	    		txtDebug.setText("DEBUG:\n" + (String) msg.obj);
	    		break;
	    	case MESSAGE_SCREEN:
	    		if(DISPLAY_MODE) {
	    			reqBrightness = (Integer) msg.obj;
		        	lp.screenBrightness = common.cap(reqBrightness*overrideBrightness, 1f, 100f) / 100.0f;
		        	getWindow().setAttributes(lp);
	    		}
	    		break;
	    	case MESSAGE_SCREEN_COLOR:
	    		String colParse = "#" + (String) msg.obj;
	    		vi.setBackgroundColor(Color.parseColor(colParse));
	    		break;
	    	case common.MESSAGE_BATTERY:
	    		pbBattery.setProgress((Integer) msg.obj);
	    		break;	
	    	}
    	}
    	
    	private void launch(String strvalues) {
    		int[] values = common.partsToInts(strvalues, " ");
    		
    		gvh.traceStart(values[1]);
    		txtRunNumber.setText("Run: " + values[1]);
    		
    		if(gvh.getWaypointPositions().getNumPositions() == values[0]) {
    			if(!launched) {
    				// GUI Updates				
    				launched = true;
    				cbRunning.setChecked(true);
    				DISPLAY_MODE = cbPaintMode.isChecked();
    				if(DISPLAY_MODE) setContentView(vi);
    				
    				gvh.traceSync("APPLICATION LAUNCH");
    				
    				logic = new LogicThread(gvh, motion);
    				created.add(logic);
    				logic.start();
    				
    	    		RobotMessage informLaunch = new RobotMessage("ALL", gvh.getName(), common.MSG_ACTIVITYLAUNCH, strvalues);
    	    		gvh.addOutgoingMessage(informLaunch);
    			}
    		} else {
    			gvh.sendMainToast("Should have " + values[0] + " waypoints, but I have " + gvh.getWaypointPositions().getNumPositions());
    		}
    	}
    	
    	private void abort() {
    		if(launched) {
    			gvh.traceSync("APPLICATION ABORT");
    			connect_disconnect();
    			launched = false;
    			connect_disconnect();
    			
        		RobotMessage informAbort = new RobotMessage("ALL", gvh.getName(), common.MSG_ACTIVITYABORT, null);
        		gvh.addOutgoingMessage(informAbort);
    		}
    	}
    };
	
    // Persistent threads
    private GPSReceiver gps = null;
    private RobotMotion motion = null;
    private LogicThread logic = null;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        created = new HashSet<Cancellable>();
        
        // Initialize preferences holder
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        selected_robot = prefs.getInt(PREF_SELECTED_ROBOT, 0);
        
        // Set up the GUI
        setupGUI();
        
        // Create the global variable holder
        HashMap<String,String> hm_participants = new HashMap<String,String>();
        for(int i = 0; i < participants.length; i++) {
        	hm_participants.put(participants[i], ips[i]);
        }        
        gvh = new globalVarHolder(hm_participants, debug_handler);
        
        // Connect
        connect_disconnect();
        
        // Register message listener
        gvh.addMsgListener(common.MSG_ACTIVITYABORT, this);
        gvh.addMsgListener(common.MSG_ACTIVITYLAUNCH, this);
        
        // Set up brightness attribute
        vi = new View(this);
    }
    
    private void updateGUI() {
    	pbBluetooth.setVisibility(bluetoothStatus == common.BLUETOOTH_CONNECTING?View.VISIBLE:View.INVISIBLE);
		cbBluetooth.setChecked(bluetoothStatus ==  common.BLUETOOTH_CONNECTED);
		gvh.sendMainMsg(common.MESSAGE_BATTERY, motion.getBatteryPercentage());
    }
    
	private void connect_disconnect() {
		if(!connected) {
			// CONNECT
			
			// Update GUI
			gvh.setDebugInfo("");
			gvh.setName(participants[selected_robot]);
	        Log.d(TAG, gvh.getName());
	        
	        // Begin persistent background threads
	        gvh.startComms();
	        gps = new GPSReceiver(gvh, gps_host, gps_port);
	        gps.start();
	        created.add(gps);
	        
	        if(motion == null) {
	        	motion = new RobotMotion(gvh, mac[selected_robot]);
	        	motion.addMotionListener(this);
	        } else {
	        	motion.resume();
	        }
		} else {
			// DISCONNECT
			
			// Shut down any created Cancellable items
			for(Cancellable c: created) {
				try {
					c.cancel();
				} catch(NullPointerException e) {}
			}
			created.clear();

			// Reset GVH's persistent threads
			gvh.stopComms();
			gvh.traceEnd();
			
			// Update GUI
			gvh.setDebugInfo("");
			txtRunNumber.setText("");
			cbRunning.setChecked(false);

			launched = false;
			
			// LIGHTPAINTING SPECIFIC:
			// Restore the view to the main GUI
			if(DISPLAY_MODE) {
				setContentView(R.layout.main);
				setupGUI();
				updateGUI();
			}

			motion.halt();
		}	
		connected = !connected;
	}

	// Robot picker
	private void pick_robot() {
		AlertDialog.Builder sel_robot_builder = new AlertDialog.Builder(this);
		sel_robot_builder.setTitle("Who Am I?");
		sel_robot_builder.setItems(participants, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dialog, int item) {
		    	selected_robot = item;
		    	txtRobotName.setText(participants[selected_robot]);
		    	saveToOptions();
		    }
		});
		AlertDialog sel_robot = sel_robot_builder.create();
		sel_robot.show();
	}

	// Save to options file
	private void saveToOptions() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor spe = prefs.edit();
		
		spe.putInt(PREF_SELECTED_ROBOT, selected_robot);
		spe.putBoolean(PREF_SELECTED_PAINTMODE, cbPaintMode.isChecked());	
		spe.commit();
	}

	private void setupGUI() {
		// Set the brightness to the default level
		defaultBrightness();

		txtRunNumber = (TextView) findViewById(R.id.txtRunNumber);
		txtRobotName = (TextView) findViewById(R.id.txtRobotName);
		cbGPS = (CheckBox) findViewById(R.id.cbGPS);
		cbBluetooth = (CheckBox) findViewById(R.id.cbBluetooth);
		cbRunning = (CheckBox) findViewById(R.id.cbRunning);
		txtDebug = (TextView) findViewById(R.id.tv_debug);
		pbBluetooth = (ProgressBar) findViewById(R.id.pb_bluetooth);
		pbBattery = (ProgressBar) findViewById(R.id.pbBattery);
		pbBattery.setMax(100);
		
		txtRunNumber.setText(" ");
		txtRobotName.setText(participants[selected_robot]);
		txtRobotName.setOnClickListener(new OnClickListener() {
			public void onClick(View arg0) {
				if(connected) connect_disconnect();
 				pick_robot();
 				connect_disconnect();
			}
		});
		
		cbPaintMode = (CheckBox) findViewById(R.id.cbDebugMode);
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		cbPaintMode.setChecked(prefs.getBoolean("PAINT_MODE", false));		
		
		cbPaintMode.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
		    	saveToOptions();
			}
		});
	}
	
	private void defaultBrightness() {
		lp = getWindow().getAttributes();
		lp.screenBrightness = defaultBrightness;
		getWindow().setAttributes(lp);
	}

	public void onStart()
    {
    	super.onStart();
    }
	
	@Override
	protected void onPause() {
		super.onPause();
	}
	
	public void messageReceied(RobotMessage m) {
		switch(m.getMID()) {
		case common.MSG_ACTIVITYLAUNCH:
			gvh.sendMainMsg(common.MESSAGE_LAUNCH, m.getContents());
			break;
		case common.MSG_ACTIVITYABORT:
			gvh.sendMainMsg(common.MESSAGE_ABORT, null);
			break;
		}
	}

	public void motionEvent(int e) {
		switch(e) {
		case common.MOT_TURNING:
			// Illuminate the screen if we're turning in place (assuming phones are in the middle!)
			overrideBrightness = 0.21f;	    			
			break;
		case common.MOT_ARCING:
			// Illuminate the screen if we're arcing 
			overrideBrightness = 1;
			break;
		case common.MOT_STRAIGHT:
			// Illuminate the screen if we're going straight 
			overrideBrightness = 1;	    
			break;
		case common.MOT_STOPPED:
			// Darken the screen if we've stopped 
			overrideBrightness = 0;	    
			break;
		}
		
		if(DISPLAY_MODE && launched) {
			gvh.sendMainMsg(MESSAGE_SCREEN, reqBrightness);
		}
	}
	
	@Override
	public void onBackPressed() {
		Log.e(TAG, "Exiting application");
		if(motion != null) {
			motion.cancel();
		}
		if(connected) connect_disconnect();
		finish();
		return;
	}
}
