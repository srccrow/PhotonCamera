package com.eszdman.photoncamera.processing.opengl.scripts;

import android.graphics.Point;
import android.util.Log;

import com.eszdman.photoncamera.R;
import com.eszdman.photoncamera.app.PhotonCamera;
import com.eszdman.photoncamera.processing.ImageProcessing;
import com.eszdman.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.eszdman.photoncamera.processing.opengl.GLFormat;
import com.eszdman.photoncamera.processing.opengl.GLOneScript;
import com.eszdman.photoncamera.processing.opengl.GLProg;
import com.eszdman.photoncamera.processing.opengl.GLTexture;

import static com.eszdman.photoncamera.processing.ImageProcessing.unlimitedCounter;
import static com.eszdman.photoncamera.processing.ImageProcessing.unlimitedEnd;

public class AverageRaw extends GLOneScript {
    GLTexture in1,in2, first,second,stack;
    private GLProg glProg;
    int used = 1;
    public AverageRaw(Point size, String name) {
        super(size, new GLCoreBlockProcessing(size,new GLFormat(GLFormat.DataType.UNSIGNED_16)), R.raw.average, name);
    }
    public void Init(){
        first = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        second = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
        stack = new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16));
    }
    GLTexture GetAlterIn(){
        if(used == 1) {
            return first;
        } else {
            return second;
        }
    }
    GLTexture GetAlterOut(){
        if(used == 1){
            used = 2;
            return second;
        } else {
            used = 1;
            return first;
        }
    }
    @Override
    public void Run() {

        //Stage 1 average alternate texture
            Compile();
            startT();
            AverageParams scriptParams = (AverageParams) additionalParams;
            glProg = glOne.glProgram;
            in1 = GetAlterIn();
            in2 = new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_16), scriptParams.inp2);
            if (in1 == null || unlimitedCounter == 1) {
                glProg.setVar("first", 1);
                if(in1 == null) Init();
            } else {
                glProg.setVar("first", 0);
                glProg.setTexture("InputBuffer", in1);
            }
            glProg.setTexture("InputBuffer2", in2);
            glProg.setVar("blacklevel", PhotonCamera.getParameters().blackLevel);
            glProg.setVar("whitelevel", (float) (PhotonCamera.getParameters().whiteLevel));
            glProg.setVar("unlimitedcount", Math.min(unlimitedCounter, 1000));

            //WorkingTexture.BufferLoad();
            glProg.drawBlocks(GetAlterOut());
            //glOne.glProcessing.drawBlocksToOutput();
            AfterRun();
            //glOne.glProgram.close();
            endT();
        //Stage 2 average stack
        if(unlimitedCounter > 100 || unlimitedEnd) {
            glProg.useProgram(R.raw.averageff);
            glProg.setTexture("InputBuffer",GetAlterIn());
            glProg.setTexture("InputBuffer2", stack);
            glProg.drawBlocks(GetAlterOut());
            GLTexture t = stack;
            if(used == 2){
                stack = second;
                second = t;
            } else {
                stack = first;
                first = t;
            }
            unlimitedCounter = 1;
        }
    }

    public void FinalScript(){


        glProg = glOne.glProgram;
        glProg.useProgram(R.raw.toraw);
        glProg.setTexture("InputBuffer",GetAlterIn());
        glProg.setVar("whitelevel",(float)(PhotonCamera.getParameters().whiteLevel));
        //in1 = WorkingTexture;
        in1 = new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_16));
        in1.BufferLoad();
        glOne.glProcessing.drawBlocksToOutput();
        first.close();
        second.close();
        in1.close();
        glProg.close();
        Output = glOne.glProcessing.mOutBuffer;
    }

    @Override
    public void AfterRun() {
        in2.close();
    }
}