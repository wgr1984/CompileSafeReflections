package de.wr.compilesafereflections;

import android.databinding.DataBindingUtil;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import de.wr.compilesafereflections.databinding.ActivityMainBinding;
import de.wr.compilesafereflections.inner.TestObjectInner;
import de.wr.compilesafereflections.inner.TestObjectInnerRelections;
import de.wr.libsimplecomposition.Reflect;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

import static io.reactivex.android.schedulers.AndroidSchedulers.mainThread;

@Reflect({AppCompatActivity.class, TestObjectInner.class})
public class MainActivity extends AppCompatActivity {
    private Disposable disposable;
    private ActivityMainBinding binding;
//    @Inject
//    Sample sampleField;

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        dispose();
        disposable = Maybe.just("test")
                .flatMap(s -> Single.just("hallo " + s)
                        .delay(2, TimeUnit.SECONDS)
                        .toMaybe()
                        )
                .filter(x -> x.contains("t"))
                .observeOn(mainThread())
                .subscribe(
                    success -> {
                        Toast.makeText(this, "Success:" + success, Toast.LENGTH_LONG).show();
                    }, error -> {
                        Toast.makeText(this, "Error:" + error, Toast.LENGTH_LONG).show();
                    }
                 );
        TestObjectInner testObjectInner = new TestObjectInner();
        TestObjectInnerRelections.setTest(testObjectInner, 15);
        TestObjectInnerRelections.testB(testObjectInner, true);
        TestObjectInnerRelections.testC(testObjectInner, 'c');
        TestObjectInnerRelections.testS(testObjectInner, (short)9);
        TestObjectInnerRelections.test(testObjectInner, 8);
        TestObjectInnerRelections.testL(testObjectInner, 123L);
        TestObjectInnerRelections.testF(testObjectInner, 18f);
        TestObjectInnerRelections.testD(testObjectInner, 12.345d);
        System.out.println(TestObjectInnerRelections.getTest(testObjectInner));
        System.out.println(TestObjectInnerRelections.testB(testObjectInner));
        System.out.println(TestObjectInnerRelections.testC(testObjectInner));
        System.out.println(TestObjectInnerRelections.testS(testObjectInner));
        System.out.println(TestObjectInnerRelections.test(testObjectInner));
        System.out.println(TestObjectInnerRelections.testL(testObjectInner));
        System.out.println(TestObjectInnerRelections.testF(testObjectInner));
        System.out.println(TestObjectInnerRelections.testD(testObjectInner));
    }

    @Override
    protected void onPause() {
        super.onPause();
        dispose();
    }

    private void dispose() {
        if (disposable != null) {
            disposable.dispose();
        }
    }
}
