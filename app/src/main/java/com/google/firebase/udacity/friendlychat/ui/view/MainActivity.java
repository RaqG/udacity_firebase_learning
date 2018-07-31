/**
 * Copyright Google Inc. All Rights Reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.udacity.friendlychat.ui.view;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TextInputLayout;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.firebase.udacity.friendlychat.BuildConfig;
import com.google.firebase.udacity.friendlychat.R;
import com.google.firebase.udacity.friendlychat.model.FriendlyMessage;
import com.google.firebase.udacity.friendlychat.ui.adapter.MessageAdapter;
import com.google.firebase.udacity.friendlychat.util.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;

import static com.google.firebase.udacity.friendlychat.util.Constants.ANONYMOUS;
import static com.google.firebase.udacity.friendlychat.util.Constants.AUTH_REQUEST_CODE;
import static com.google.firebase.udacity.friendlychat.util.Constants.DEFAULT_MSG_LENGTH_LIMIT;
import static com.google.firebase.udacity.friendlychat.util.Constants.FRIENDLY_MSG_LENGTH_KEY;
import static com.google.firebase.udacity.friendlychat.util.Constants.PHOTO_PICKER;

public class MainActivity extends AppCompatActivity {

    @BindView(R.id.relative_layout)
    RelativeLayout content;
    @BindView(R.id.messageListView)
    ListView mMessageListView;
    @BindView(R.id.progressBar)
    ProgressBar mProgressBar;
    @BindView(R.id.photoPickerButton)
    ImageButton mPhotoPickerButton;
    @BindView(R.id.textContainer)
    TextInputLayout inputLayout;
    @BindView(R.id.messageEditText)
    EditText mMessageEditText;
    @BindView(R.id.sendButton)
    Button mSendButton;

    private String mUsername;
    private MessageAdapter mMessageAdapter;

    private FirebaseDatabase database;
    private DatabaseReference dbReference;
    private FirebaseAuth auth;
    private FirebaseStorage storage;
    private StorageReference storageReference;
    private FirebaseRemoteConfig remoteConfig;

    private ChildEventListener eventListener;
    private FirebaseAuth.AuthStateListener stateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        init();

        // ImagePickerButton shows an image picker to upload a image for a message
        mPhotoPickerButton.setOnClickListener(onPhotoButtonClicked());

        // Enable Send button when there's text to send
        mMessageEditText.addTextChangedListener(onChangeText());
        mMessageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Constants.DEFAULT_MSG_LENGTH_LIMIT)});

        // Send button sends a message and clears the EditText
        mSendButton.setOnClickListener(clickOnSendButton());

        stateListener = initStateListener();
    }

    private View.OnClickListener onPhotoButtonClicked() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                it.setType("image/jpeg");
                it.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                startActivityForResult(Intent.createChooser(it, "Complete action using"),
                        Constants.PHOTO_PICKER);
            }
        };
    }


    private void init() {
        // Initialize message ListView and its adapter
        List<FriendlyMessage> friendlyMessages = new ArrayList<>();
        mMessageAdapter = new MessageAdapter(this, R.layout.item_message, friendlyMessages);
        mMessageListView.setAdapter(mMessageAdapter);

        // Initialize progress bar
        mProgressBar.setVisibility(ProgressBar.INVISIBLE);

        initFirebase();
    }

    private void initFirebase() {
        database = FirebaseDatabase.getInstance();
        auth = FirebaseAuth.getInstance();
        storage = FirebaseStorage.getInstance();
        dbReference = database.getReference().child(Constants.CHILD_REFERENCE);
        storageReference = storage.getReference().child("chat_photos");
        remoteConfig = FirebaseRemoteConfig.getInstance();
        configFirebaseRemoteConfig();
    }

    private void configFirebaseRemoteConfig() {
        FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(BuildConfig.DEBUG)
                .build();
        remoteConfig.setConfigSettings(settings);

        Map<String, Object> defaultConfigMap = new HashMap<>();
        defaultConfigMap.put(Constants.FRIENDLY_MSG_LENGTH_KEY, DEFAULT_MSG_LENGTH_LIMIT);
        remoteConfig.setDefaults(defaultConfigMap);
        fetchConfig();
    }

    private void fetchConfig() {
        long cacheExpiration = 3600;

        if (remoteConfig.getInfo().getConfigSettings().isDeveloperModeEnabled()) {
            cacheExpiration = 0;
        }
        remoteConfig.fetch(cacheExpiration)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        remoteConfig.activateFetched();
                        applyRetrievedLengthLimit();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Snackbar.make(content, R.string.connection_failure_message, Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    private void applyRetrievedLengthLimit() {
        Long friendly_msg_length = remoteConfig.getLong(FRIENDLY_MSG_LENGTH_KEY);
        mMessageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(friendly_msg_length.intValue())});
        inputLayout.setCounterMaxLength(friendly_msg_length.intValue());
    }

    private TextWatcher onChangeText() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    mSendButton.setEnabled(true);
                } else {
                    mSendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        };
    }

    private View.OnClickListener clickOnSendButton() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FriendlyMessage friendlyMessage = new FriendlyMessage(mMessageEditText.getText().toString(), mUsername, null);
                dbReference.push().setValue(friendlyMessage);
                // Clear input box
                mMessageEditText.setText("");
            }
        };
    }

    private ChildEventListener initEventListener() {
        return new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                FriendlyMessage friendlyMessage = dataSnapshot.getValue(FriendlyMessage.class);
                mMessageAdapter.add(friendlyMessage);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot dataSnapshot) {
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
            }
        };
    }

    private FirebaseAuth.AuthStateListener initStateListener() {
        return new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    onSignedInInit(firebaseAuth.getCurrentUser().getDisplayName());
                } else {
                    onSignedOutCleanup();
                    startActivityForResult(
                            AuthUI.getInstance()
                                    .createSignInIntentBuilder()
                                    .setIsSmartLockEnabled(false)
                                    .setAvailableProviders(
                                            Arrays.asList(
                                                    new AuthUI.IdpConfig.EmailBuilder().build(),
                                                    new AuthUI.IdpConfig.GoogleBuilder().build()
                                            ))
                                    .build(),
                            Constants.AUTH_REQUEST_CODE
                    );
                }
            }
        };
    }

    private void onSignedInInit(String currentUser) {
        mUsername = currentUser;
        attachDatabaseReadListener();
    }

    private void onSignedOutCleanup() {
        mUsername = ANONYMOUS;
        mMessageAdapter.clear();
        detachDatabaseReadListener();
    }

    private void attachDatabaseReadListener() {
        if (eventListener == null) {
            eventListener = initEventListener();
            dbReference.addChildEventListener(eventListener);
        }
    }

    private void detachDatabaseReadListener() {
        if (eventListener != null) {
            dbReference.removeEventListener(eventListener);
            eventListener = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case AUTH_REQUEST_CODE:
                if (resultCode == RESULT_OK) {
                    Snackbar.make(content, R.string.signed_in, Snackbar.LENGTH_LONG).show();
                } else if (resultCode == RESULT_CANCELED) {
                    Snackbar.make(content, R.string.signed_in_canceled, Snackbar.LENGTH_LONG).show();
                    finish();
                }
                break;
            case PHOTO_PICKER:
                if (resultCode == RESULT_OK) {
                    Uri selectedImage = data.getData();
                    StorageReference photoRef = storageReference.child(selectedImage.getLastPathSegment());
                    UploadTask uploadTask = photoRef.putFile(selectedImage);
                    uploadPhotoToStorage(uploadTask, photoRef);
                }
                break;
        }
    }

    private void uploadPhotoToStorage(UploadTask uploadTask, final StorageReference photoRef) {
        uploadTask
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Snackbar.make(content, R.string.failure + e.getMessage(), Snackbar.LENGTH_LONG).show();
                    }
                })
                .addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                        photoRef.getDownloadUrl().addOnSuccessListener(new OnSuccessListener<Uri>() {
                            @Override
                            public void onSuccess(Uri uri) {
                                String imagePath = uri.toString();
                                FriendlyMessage message = new FriendlyMessage(null, mUsername, imagePath);
                                dbReference.push().setValue(message);
                            }
                        });
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.sign_out_menu:
                AuthUI.getInstance().signOut(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        auth.addAuthStateListener(stateListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (auth != null)
            auth.removeAuthStateListener(stateListener);
        detachDatabaseReadListener();
        mMessageAdapter.clear();
    }
}
