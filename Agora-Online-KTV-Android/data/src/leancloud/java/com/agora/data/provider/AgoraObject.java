package com.agora.data.provider;

import androidx.annotation.NonNull;

import com.agora.data.sync.IAgoraObject;
import com.agora.data.sync.SyncManager;

import cn.leancloud.LCObject;

/**
 * @author chenhengfei(Aslanchen)
 * @date 2021/5/25
 */
public class AgoraObject implements IAgoraObject {

    private final LCObject LCObject;

    public AgoraObject(LCObject LCObject) {
        this.LCObject = LCObject;
    }

    @Override
    public <T> T toObject(@NonNull Class<T> valueType) {
        return SyncManager.getConverter().toObject(LCObject.toJSONObject().toJSONString(), valueType);
    }

    @Override
    public String getId() {
        return LCObject.getObjectId();
    }

    public Object get(String key) {
        Object object = LCObject.get(key);
        if (object instanceof LCObject) {
            object = new AgoraObject((LCObject) object);
        }
        return object;
    }
}
