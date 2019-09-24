package com.supereal.bigfile.service.Impl;

import com.alibaba.fastjson.JSONObject;
import com.supereal.bigfile.singleton.FileSingleton;
import com.supereal.bigfile.common.Constant;
import com.supereal.bigfile.common.ErrorCode;
import com.supereal.bigfile.dataobject.UploadFile;
import com.supereal.bigfile.exception.BusinessException;
import com.supereal.bigfile.form.FileForm;
import com.supereal.bigfile.repository.UploadFileRepository;
import com.supereal.bigfile.service.UploadService;
import com.supereal.bigfile.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Create by tianci
 * 2019/1/11 11:24
 * @author bitmain
 */

@Service
@Slf4j
public class UploadServiceImpl implements UploadService {

    private SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");

    @Autowired
    UploadFileRepository uploadFileRepository;

    @Override
    public Result findByFileMd5(FileForm form) {
        /*UploadFile uploadFile = uploadFileRepository.findByFileMd5(form.getMd5());*/

        UploadFile uploadFile = uploadFileRepository.findUploadFileByName(form.getName());

        String fileId = KeyUtil.genUniqueKey();
        if(uploadFile != null){
            fileId = uploadFile.getId();
        }

        JSONObject json = new JSONObject();
        json.put("fileId",fileId);
        json.put("date", simpleDateFormat.format(new Date()));
        return Result.ok(json);
    }

    @Override
    public Result checkPartFileIsExist(FileForm form,boolean combineFlag){
        try{
            long start = System.currentTimeMillis();
            String fileId = form.getFileId();
            String partMd5 = form.getPartMd5();
            Integer index = Integer.parseInt(form.getIndex());

            Integer total = Integer.parseInt(form.getTotal());
            //文件存储位置
            String saveDirectory = Constant.PATH + File.separator + fileId;
            File file = new File(saveDirectory, fileId + "_" + index);
            if(!file.exists()){
                long end = System.currentTimeMillis();
                log.info("完成校验1111index:" + form.getIndex() + "，耗时：" + ( end - start )+ "毫秒");
                return Result.ok("分片文件不存在，可以直接上传");
            }
            //获取分片文件的md5
            String md5Str = FileMd5Util.getFileMD5(file);
            if (md5Str != null && md5Str.length() == 31) {
                log.info("前端传过来的文件md5:" + partMd5 + ",获取本地文件的md5:" + md5Str);
                md5Str = "0" + md5Str;
            }
            if(combineFlag){
                //异步拼接所有文件任务
                ThreadUtil.run(() ->{
                    //如果所有分片文件都存在，则执行合并分片文件操作
                    File allFile = new File(saveDirectory);
                    File[] fileArray = allFile.listFiles();
                    int fileCount = fileArray == null ? 0 : fileArray.length;
                    log.info("校验文件是否存在，fileCount:" + fileCount + ",total：" + form.getTotal() + ",index:" + form.getIndex());
                    if (fileCount > 0 && fileCount == total) {
                        log.info("检验文件后开始合并文件");
                        combineAllFile(form);
                    }
                });
            }
            FileSingleton fileSingleton = FileSingleton.getInstance();
            if(fileSingleton.getFileIdsIndex(fileId) >= total){
                log.info("单例模式中记录的文件上传个数：" + fileSingleton.getFileIdsIndex(fileId) + "与文件总数有误，total:" + total + "，从单例中删除该记录");
                fileSingleton.removeFileId(fileId);
            }
            if (md5Str != null && md5Str.equals(partMd5)) {
                //该分片文件已上传过，记录加1
                fileSingleton.setFileIdsIndex(fileId);
                //分片已上传过
                long end = System.currentTimeMillis();
                log.info("完成校验222index:" + form.getIndex() + "，耗时：" + ( end - start )+ "毫秒，已上传数：" + fileSingleton.getFileIdsIndex(fileId));
                return Result.error("该分片文件已上传过");
            } else {
                //分片未上传
                long end = System.currentTimeMillis();
                log.info("完成校验333index:" + form.getIndex() + "，耗时：" + ( end - start )+ "毫秒，已上传数：" + fileSingleton.getFileIdsIndex(fileId));
                return Result.ok("该分片文件未上传过，可以执行上传");
            }
        }catch (Exception e){
            //校验文件分片发生异常，直接让重新上传
            log.error("校验文件分片发生异常，直接让重新上传,e:" + e.getMessage());
            return Result.ok("校验文件分片出错，允许直接上传文件");
        }

    }


    @Override
    public  Result realUpload(FileForm form) {
        MultipartFile multipartFile = form.getData();
        long start = System.currentTimeMillis();
        String fileId = form.getFileId();
        Integer index = Integer.valueOf(form.getIndex());

        String md5 = form.getMd5();
        Integer total = Integer.valueOf(form.getTotal());
        String fileName = form.getName();
        String size = form.getSize();
        String suffix = NameUtil.getExtensionName(fileName);

        String saveDirectory = Constant.PATH + File.separator + fileId;
        String filePath = saveDirectory + File.separator + fileId + "." + suffix;
        //验证路径是否存在，不存在则创建目录
        File allFile = new File(saveDirectory);
        if (!allFile.exists()) {
            allFile.mkdirs();
        }
        //文件分片位置
        File file = new File(saveDirectory, fileId + "_" + index);


        FileSingleton fileSingleton = FileSingleton.getInstance();
        Result checkResult = checkPartFileIsExist(form,false);
        if(Objects.equals(checkResult.getCode(), ErrorCode.RESULT_SUCCESS.getCode())){
            //分片上传过程中出错,有残余时需删除分块后,重新上传
            if (file.exists()) {
                file.delete();
            }
            //修改FileRes记录为上传成功
            UploadFile uploadFile = new UploadFile();
            uploadFile.setId(fileId);
            uploadFile.setStatus(1);
            uploadFile.setName(fileName);
            uploadFile.setFileMd5(md5);
            uploadFile.setSuffix(suffix);
            uploadFile.setPath(filePath);
            uploadFile.setSize(size);
            uploadFile.setDeleted(0);
            uploadFile.setTotalBlock(total);
            uploadFile.setCreateTime(new Date());
            fileSingleton.setFileIdsIndex(fileId);
            uploadFile.setFileIndex(fileSingleton.getFileIdsIndex(fileId));
            try{
                //防止保存出错，文件存储不了
                uploadFileRepository.save(uploadFile);
            }catch (Exception e){
                ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
                log.error("存储数据发生错误：" + res.getMsg());
            }


            try{
                multipartFile.transferTo(new File(saveDirectory, fileId + "_" + index));
                if(Objects.equals(fileSingleton.getFileIdsIndex(fileId),total)){
                    //开一个线程单独去执行合并文件操作
                    ThreadUtil.run(() ->{
                        log.info("开始合并文件，已上传文件数：" + fileSingleton.getFileIdsIndex(fileId));
                        fileSingleton.removeFileId(fileId);
                        log.info("开始合并文件，删除单例数据后，已上传文件数：" + fileSingleton.getFileIdsIndex(fileId));
                        combineAllFile(form);
                    });
                }
                long end = System.currentTimeMillis();
                log.info("完成上传分片111，index:" + form.getIndex() + ",total:" + total +
                        ",已上传文件数：" + fileSingleton.getFileIdsIndex(fileId) + ",耗时：" + ( end - start )+ "毫秒");
                return Result.ok("分片文件上传成功！");
            }catch (Exception e){
                ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
                log.error("存储分片文件出错：" + res.getMsg());
                return Result.ok("存储分片文件出错：" + res.getMsg());
            }

        }else{
            //执行这一步的目的就是防止前端不执行校验文件操作，如果分片文件都已上传，前端不做校验的话就不会拼接分片
            if(Objects.equals(fileSingleton.getFileIdsIndex(fileId),total)){
                //开一个线程单独去执行合并文件操作
                ThreadUtil.run(() ->{
                    log.info("开始合并文件，已上传文件数：" + fileSingleton.getFileIdsIndex(fileId));
                    fileSingleton.removeFileId(fileId);
                    log.info("开始合并文件，删除单例数据后，已上传文件数：" + fileSingleton.getFileIdsIndex(fileId));
                    combineAllFile(form);
                });
            }
            long end = System.currentTimeMillis();
            System.out.println("完成上传分片222index:" + form.getIndex() + "，耗时：" + ( end - start )+ "毫秒");
            return Result.ok("分片文件已存在！");
        }

    }

    public synchronized Result combineAllFile(FileForm form) {

        try{
            String fileName = form.getName();
            String suffix = NameUtil.getExtensionName(fileName);

            String saveDirectory = Constant.PATH + File.separator + form.getFileId();
            String finalFilePath = saveDirectory + File.separator + form.getFileId() + "." + suffix;


            Integer total = Integer.valueOf(form.getTotal());
            File finalFile = new File(finalFilePath);
            if (finalFile.exists()) {
                finalFile.delete();
            }
            File allFile = new File(saveDirectory);
            File[] fileArray = allFile.listFiles();
            int fileCount = fileArray == null ? 0 : fileArray.length;

            log.info("尝试合并文件，index：" + form.getIndex() + "，fileCount：" + fileCount);
            if (fileCount > 0 && fileCount == total) {
                //只要执行合并操作了，就要把单例中记录的数据删掉
                FileSingleton fileSingleton = FileSingleton.getInstance();
                fileSingleton.removeFileId(form.getFileId());
                log.info("获取fileId:" + form.getFileId() + ",下的文件列表总数:" + fileCount + ",和total:" + total + "，开始合并文件");
                //分块全部上传完毕,合并
                File newFile = new File(saveDirectory, form.getFileId() + "." + suffix);
                //文件追加写入
                FileOutputStream outputStream = new FileOutputStream(newFile, true);
                byte[] byt = new byte[20 * 1024 * 1024];
                int len;
                //分片文件
                FileInputStream temp = null;
                for (int i = 0; i < total; i++) {
                    int j = i + 1;
                    temp = new FileInputStream(new File(saveDirectory, form.getFileId() + "_" + j));
                    while ((len = temp.read(byt)) != -1) {
                        outputStream.write(byt, 0, len);
                    }
                }
                //关闭流
                temp.close();
                outputStream.close();
                //修改FileRes记录为上传成功
                UploadFile uploadFile = new UploadFile();
                uploadFile.setId(form.getFileId());
                uploadFile.setStatus(2);
                uploadFile.setName(form.getName());
                uploadFile.setFileMd5(form.getMd5());
                uploadFile.setSuffix(suffix);
                uploadFile.setPath(finalFilePath);
                uploadFile.setSize(form.getSize());
                uploadFile.setDeleted(0);
                uploadFile.setTotalBlock(total);
                uploadFile.setFileIndex(total);
                uploadFileRepository.save(uploadFile);
                return Result.ok("所有分片上传完成，文件合并成功");

            } else {
                log.error("获取fileId:" + form.getFileId() + ",下的文件为空，或该文件列表总数:" + fileCount + ",和total:" + total + "不一致，不执行合并文件");
                return Result.error("获取fileId:" + form.getFileId() + ",下的文件为空，或该文件列表总数:" + fileCount + ",和total:" + total + "不一致");
            }
        }catch (Exception e){
            ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
            throw new BusinessException("合并文件出错：" + res.getMsg());
        }

    }




    @Override
    public Result realUploadByQueue() {
        ThreadPoolExecutor startThreadPool = new ThreadPoolExecutor(50, 200, 1, TimeUnit.SECONDS, new LinkedBlockingDeque<>());
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        log.info("启动线程前，当前线程总数为：" + bean.getThreadCount());

        FileSingleton fileSingleton = FileSingleton.getInstance();
        ConcurrentLinkedQueue<FileForm> fileFormList = fileSingleton.getFileFormQueueList();

        while (true){
            //有问题，会报空指针，但也能正常运行
            startThreadPool.execute(()->{
                log.info("启动线程后，当前线程总数为：" + bean.getThreadCount());
                FileForm form = fileFormList.poll();
                realUpload(form);
            });
            if(fileFormList.isEmpty()){
                log.info("队列中数据为空，结束读取队列");
                fileSingleton.setFlag(true);
                break;
            }
        }
        //判断线程是否执行完毕
        startThreadPool.shutdown();
        log.info("当前线程池活跃线程总数为1111：" + startThreadPool.getActiveCount());
        try{
            while (true) {
                if (startThreadPool.isTerminated()) {
                    log.info("批量启动实时任务线程执行中....../////......");
                    log.info("当前线程池活跃线程总数为2222：" + startThreadPool.getActiveCount());
                    break;
                }
                Thread.sleep(200);
            }
        }catch (Exception e){
            log.error("停止批量启动实时任务线程出错：" + e.getMessage());
        }
        return Result.ok("文件上传中，请稍后");
    }


}
