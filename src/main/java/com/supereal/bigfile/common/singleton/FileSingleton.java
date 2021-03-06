package com.supereal.bigfile.common.singleton;


import com.alibaba.fastjson.JSONObject;
import com.supereal.bigfile.vo.FileForm;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author bitmain
 */
@Slf4j
public class FileSingleton {

    /**
     * 类级的内部类，也就是静态的成员式内部类，该内部类的实例与外部类的实例
     * 没有绑定关系，而且只有被调用到才会装载，从而实现了延迟加载
     */
    private static class SingletonHolder {
        /**
         * 静态初始化器，由JVM来保证线程安全
         */
        private static FileSingleton instance = new FileSingleton();
        private static ConcurrentLinkedQueue<String> stringQueueList = new ConcurrentLinkedQueue();
        private static ConcurrentLinkedQueue<FileForm> fileFormList = new ConcurrentLinkedQueue();
        private static boolean flag = true;

        //用来记录分块文件上传的数
        private static JSONObject fileIds = new JSONObject();

        //用来记录分块文件上传的数
        private static JSONObject fileIdFlags = new JSONObject();
    }


    /**
     * 私有化构造方法
     */
    private FileSingleton() {
    }

    public static FileSingleton getInstance() {
        return SingletonHolder.instance;
    }

    public ConcurrentLinkedQueue<String> getStringQueueList(){
        return SingletonHolder.stringQueueList;
    }

    public ConcurrentLinkedQueue<FileForm> getFileFormQueueList(){
        return SingletonHolder.fileFormList;
    }

    public boolean getFlag(){
        return SingletonHolder.flag;
    }

    public synchronized ConcurrentLinkedQueue<String> addMsgToQueue(String msg){
        ConcurrentLinkedQueue<String> result = getStringQueueList();
        result.offer(msg);
        return result;
    }

    public synchronized ConcurrentLinkedQueue<FileForm> addFileFormToQueue(FileForm form){
        ConcurrentLinkedQueue<FileForm> result = getFileFormQueueList();
        result.offer(form);
        return result;
    }

    public synchronized void setFlag(boolean flag){
        SingletonHolder.flag = flag;
    }


    public JSONObject getFileIds(){
        return SingletonHolder.fileIds;
    }
    public JSONObject getFileIdFlags(){
        return SingletonHolder.fileIdFlags;
    }

    public Integer getFileIdsIndex(String fileId){
        JSONObject fileIds = getFileIds();
        Integer index = fileIds.getInteger(fileId);
        Integer result = index == null?0:index;
        return result;
    }

    public synchronized void setFileIds(JSONObject fileIds){
        SingletonHolder.fileIds = fileIds;
    }

    public synchronized void setFileIdFlags(JSONObject fileIdFlags){
        SingletonHolder.fileIdFlags = fileIdFlags;
    }

    public synchronized void setFileIdsIndex(String fileId){
        //将对应的fileId的文件存储数据加1
        JSONObject json = getFileIds();
        Integer index = getFileIdsIndex(fileId);
        index++;
        json.put(fileId,index);
        setFileIds(json);
    }

    public void removeFileId(String fileId){
        JSONObject json = getFileIds();
        json.remove(fileId);
    }

    public boolean getFileIdIsCombine(String fileId){
        JSONObject json = getFileIdFlags();
        boolean fileIdFlag = json.getBoolean(fileId);
        return fileIdFlag;
    }

    public synchronized void setFileIdIsCombine(String fileId,boolean fileIdFlag){
        JSONObject json = getFileIdFlags();
        if(fileIdFlag){
            json.put(fileId,fileIdFlag);
        }else{
            json.remove(fileId);
        }

    }
}
