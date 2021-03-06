package com.supereal.bigfile.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.supereal.bigfile.common.singleton.FileSingleton;
import com.supereal.bigfile.common.Constant;
import com.supereal.bigfile.common.ErrorCode;
import com.supereal.bigfile.entity.UploadFile;
import com.supereal.bigfile.enums.FileStatus;
import com.supereal.bigfile.exception.BusinessException;
import com.supereal.bigfile.vo.FileForm;
import com.supereal.bigfile.repository.UploadFileRepository;
import com.supereal.bigfile.service.UploadFileService;
import com.supereal.bigfile.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.csource.fastdfs.TrackerClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Create by tianci
 * 2019/1/11 11:24
 *
 * @author bitmain
 */

@Service
@Slf4j
public class UploadFileServiceImpl implements UploadFileService {

    @Resource
    private TrackerClient trackerClient;

    private SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");

    /**
     * 在校验分片文件是否上传过时，如果不做个判断，如果分片都上传过，会不断去重新合并
     */
//    private static JSONObject combineJsonFlag = new JSONObject();

    /**
     * 用于记录当校验文件分片时，是否执行合并文件操作，只有校验最后一个分片时候才去执行合并
     */
//    private static ConcurrentHashMap fileIdCheckMap = new ConcurrentHashMap();

    @Autowired
    UploadFileRepository uploadFileRepository;

    @Value("${fastdfs.http_url}")
    private String httUrl;

    @Resource(name = "remoteRestTemplate")
    private RestTemplate remoteRestTemplate;

    @Override
    public Result findByFileMd5(FileForm form) {
        /*UploadFile uploadFile = uploadFileRepository.findByFileMd5(form.getMd5());*/
        UploadFile uploadFile = uploadFileRepository.findUploadFileByName(form.getName());

        String fileId = KeyUtil.genUniqueKey();
        if (uploadFile != null) {
            fileId = uploadFile.getId();
        }
        JSONObject json = new JSONObject();
        json.put("fileId", fileId);
        json.put("date", simpleDateFormat.format(new Date()));
        if (uploadFile == null) {
            return Result.ok(json);
        } else {
            if (FileStatus.FINISH.getCode() == uploadFile.getStatus()) {
                return Result.errorResult(json);
            }
            return Result.ok(json);
        }

    }

    @Override
    public Result checkPartFileIsExist(FileForm form, boolean combineFlag) {
        try {

            long start = System.currentTimeMillis();
            String fileId = form.getFileId();
            String partMd5 = form.getPartMd5();
            Integer index = Integer.parseInt(form.getIndex());
            Integer total = Integer.parseInt(form.getTotal());
            //文件存储位置
            String saveDirectory = Constant.PATH + File.separator + fileId;
            File file = new File(saveDirectory, fileId + "_" + index);
            if (!file.exists()) {
                long end = System.currentTimeMillis();
                log.info("分片文件不存在，可以直接上传>>>>>>>>>>index:" + form.getIndex() + "，耗时：" + (end - start) + "毫秒");
                return Result.ok("分片文件不存在，可以直接上传");
            }
            //获取分片文件的md5
            String md5Str = FileMd5Util.getFileMD5(file);
            if (md5Str != null && md5Str.length() == 31) {
                log.info("前端传过来的文件md5:" + partMd5 + ",获取本地文件的md5:" + md5Str);
                md5Str = "0" + md5Str;
            }
            if (md5Str != null && md5Str.equals(partMd5)) {
//            String fileIdFlag = combineJsonFlag.getString(fileId);
//            log.error("判断fileId:" + fileId + ",是否可以执行合并操作标记值：" + fileIdFlag);
                int fileCount = FileUtil.getPathFileCount(saveDirectory, form.getFileId());
                //TODO 前端如果不是按顺序校验，最后一个index就可能不是最后上传
                if (combineFlag && fileCount == total && total.equals(index)) {
                    //异步拼接所有文件任务
                    ThreadUtil.run(() -> {
                        //当校验分片文件后，如果有多个分片文件上传过，此时文件总数又和上传的总数想等就可能执行多次合并文件的操作
                        //如果所有分片文件都存在，则执行合并分片文件操作
                        combineAllFile(form,"校验分片文件后尝试合并文件，index:" + index);
                    });
                }
                //分片已上传过
                long end = System.currentTimeMillis();
                log.info("检验分片时，获取已上传的分片总数：" + fileCount + ",该分片文件已上传过，不需要重复上传>>>>>>>>>>>index:" + form.getIndex() + "，耗时：" + (end - start) + "毫秒");
                return Result.error("该分片文件已上传过，不需要重复上传");
            } else {
                //分片未上传
                long end = System.currentTimeMillis();
                log.info("该分片上传过，但和服务器不匹配，需要重新上传>>>>>>>>>index:" + form.getIndex() + "，耗时：" + (end - start) + "毫秒");
                return Result.ok("该分片文件未上传过，可以执行上传");
            }
        } catch (Exception e) {
            //校验文件分片发生异常，直接让重新上传
            log.error("校验文件分片发生异常，直接让重新上传,e:" + e.getMessage());
            return Result.ok("校验文件分片出错，允许直接上传文件");
        }

    }

    @Override
    public synchronized  void saveUploadFile(FileForm form, String saveDirectory, Integer status, String fastPath) {
        try {
            Integer total = Integer.valueOf(form.getTotal());
            String suffix = NameUtil.getExtensionName(form.getName());
            String filePath = saveDirectory + File.separator + form.getFileId() + "." + suffix;
            //修改FileRes记录为上传成功
            UploadFile uploadFile = uploadFileRepository.findUploadFileById(form.getFileId());
            if (uploadFile == null) {
                uploadFile = new UploadFile();
                uploadFile.setCreateTime(new Date());
            }
            uploadFile.setId(form.getFileId());
            uploadFile.setStatus(status);
            uploadFile.setName(form.getName());
            uploadFile.setFileMd5(form.getMd5());
            uploadFile.setSuffix(suffix);
            uploadFile.setPath(filePath);
            if(StringUtils.isBlank(fastPath)){
                fastPath = uploadFile.getFastPath();
            }
            uploadFile.setFastPath(fastPath);
            uploadFile.setSize(form.getSize());
            uploadFile.setDeleted(0);
            uploadFile.setTotalBlock(total);
            uploadFile.setUpdateTime(new Date());
            //设置已经上传了多少个分片
            int fileCount = FileUtil.getPathFileCount(saveDirectory,form.getFileId());
            uploadFile.setFileIndex(fileCount);
            //防止保存出错，文件存储不了
            uploadFileRepository.save(uploadFile);
        } catch (Exception e) {
            ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
            log.error("存储数据发生错误：" + res.getMsg());
        }
    }


    @Override
    public Result realUpload(FileForm form) {
        MultipartFile multipartFile = form.getData();
        long start = System.currentTimeMillis();
        String fileId = form.getFileId();
        Integer index = Integer.valueOf(form.getIndex());
        Integer total = Integer.valueOf(form.getTotal());
        //文件存储的位置
        String saveDirectory = Constant.PATH + File.separator + fileId;
        //验证路径是否存在，不存在则创建目录
        File allFile = new File(saveDirectory);
        if (!allFile.exists()) {
            allFile.mkdirs();
        }
        //文件分片位置
        File file = new File(saveDirectory, fileId + "_" + index);
        //校验文件分片是否存在
        Result checkResult = checkPartFileIsExist(form, false);
        if (Objects.equals(checkResult.getCode(), ErrorCode.RESULT_SUCCESS.getCode())) {
            //分片上传过程中出错,有残余时需删除分块后,重新上传
            if (file.exists()) {
                file.delete();
            }
            try {
                multipartFile.transferTo(new File(saveDirectory, fileId + "_" + index));
                //如果保存上传记录如果在上传分片文件之前，记录的上传分片总数会少一个
                saveUploadFile(form, saveDirectory, FileStatus.NOT_FINISH.getCode(), null);
                int fileCount = FileUtil.getPathFileCount(saveDirectory,form.getFileId());
                if (fileCount == total) {
                    //开一个线程单独去执行合并文件操作,下面方法会做校验是否一定合并,上传文件后需要重新执行合并文件
                    ThreadUtil.run(() -> {
                        combineAllFile(form,"上传分片文件后尝试合并文件，index:" + index);
                    });
                }
                long end = System.currentTimeMillis();
                log.info("上传分片文件后，已上传的分片总数：" + fileCount + ",完成上传分片>>>>>>>，index:" + form.getIndex() + ",total:" + total + ",耗时：" + (end - start) + "毫秒");
                return Result.ok("分片文件上传成功！");
            } catch (Exception e) {
                ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
                log.error("存储分片文件出错：" + res.getMsg());
                return Result.ok("存储分片文件出错：" + res.getMsg());
            }

        } else {
            log.error("上传文件校验后执行合并");
            //执行这一步的目的就是防止前端不执行校验文件操作，如果分片文件都已上传，前端不做校验的话就不会拼接分片
            //开一个线程单独去执行合并文件操作，TODO 如果前端不做校验直接把分片文件传过来了，我再去做校验，如果分片都存在，可能会多次合并文件
            if (FileUtil.getPathFileCount(saveDirectory,form.getFileId()) == total) {
                ThreadUtil.run(() -> {
                    combineAllFile(form,"上传分片文件时，校验分片文件后尝试一次合并文件，index:" + index);
                });
            }
            long end = System.currentTimeMillis();
            log.info("文件分片已存在，不再上传，尝试合并文件操作，index:" + form.getIndex() + "，耗时：" + (end - start) + "毫秒");
            return Result.ok("分片文件已存在！");
        }

    }

    @Override
    public synchronized Result combineAllFile(FileForm form,String flag) {
        log.error("调用合并文件方法标记：" + flag);
        try {
            String fileName = form.getName();
            String suffix = NameUtil.getExtensionName(fileName);

            String saveDirectory = Constant.PATH + File.separator + form.getFileId();
            String finalFilePath = saveDirectory + File.separator + form.getFileId() + "." + suffix;
            Integer total = Integer.valueOf(form.getTotal());
            File finalFile = new File(finalFilePath);
            if (finalFile.exists()) {
                finalFile.delete();
            }
            int fileCount = FileUtil.getPathFileCount(saveDirectory, form.getFileId());
            log.info("合并文件方法，校验合并文件条件，fileCount:" + fileCount + ",total：" + form.getTotal() + ",index:" + form.getIndex());
            if (fileCount > 0 && fileCount == total) {
                //只要执行合并操作了，就要把单例中记录的数据删掉
                log.info("检验文件后,已上传文件数符合文件总数，开始执行合并文件操作，fileId:" + form.getFileId() + ",下的文件列表总数:" + fileCount + ",和total:" + total);
                //分块全部上传完毕,合并
                File newFile = new File(saveDirectory, form.getFileId() + "." + suffix);
                //文件追加写入
                FileOutputStream outputStream = new FileOutputStream(newFile, true);
                byte[] byt = new byte[20 * 1024 * 1024];
                int len;
                //分片文件
                FileInputStream temp = null;
                for (int i = 1; i <= total; i++) {
                    temp = new FileInputStream(new File(saveDirectory, form.getFileId() + "_" + i));
                    while ((len = temp.read(byt)) != -1) {
                        outputStream.write(byt, 0, len);
                    }
                }
                //关闭流
                temp.close();
                outputStream.close();

                String fastPath = null;
                UploadFile uploadFile = uploadFileRepository.findUploadFileById(form.getFileId());
                if (uploadFile == null) {
                    uploadFile = new UploadFile();
                }
                if (!checkFastFileIsExist(uploadFile.getFastPath())) {
                    FileInputStream inputStream = new FileInputStream(finalFile);
                    MultipartFile multipartFile = new MockMultipartFile(finalFile.getName(), inputStream);
                    Result<String> result = uploadToFastDfs(multipartFile, suffix);
                    fastPath = result.getResult();
                    if (StringUtils.isBlank(fastPath)) {
                        log.error("文件fileId:" + form.getFileId() + "，往fastDFS存文件失败");
                    } else {
                        log.info("文件fileId:" + form.getFileId() + "，往fastDFS存文件成功");
                    }
                } else {
                    fastPath = uploadFile.getFastPath();
                    log.info("文件fileId:" + form.getFileId() + "，已存在fastDFS，不重复上传");
                }
                //只有正常合并文件后才去更新文件状态
                saveUploadFile(form, saveDirectory, FileStatus.FINISH.getCode(), fastPath);

                return Result.ok("所有分片上传完成，文件合并成功");

            } else {
                log.error("获取fileId:" + form.getFileId() + ",下的文件为空，或该文件列表总数:" + fileCount + ",和total:" + total + "不一致，不执行合并文件");
                return Result.error("获取fileId:" + form.getFileId() + ",下的文件为空，或该文件列表总数:" + fileCount + ",和total:" + total + "不一致");
            }
        } catch (Exception e) {
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

        while (true) {
            //有问题，会报空指针，但也能正常运行
            startThreadPool.execute(() -> {
                log.info("启动线程后，当前线程总数为：" + bean.getThreadCount());
                FileForm form = fileFormList.poll();
                realUpload(form);
            });
            if (fileFormList.isEmpty()) {
                log.info("队列中数据为空，结束读取队列");
                fileSingleton.setFlag(true);
                break;
            }
        }
        //判断线程是否执行完毕
        startThreadPool.shutdown();
        log.info("当前线程池活跃线程总数为1111：" + startThreadPool.getActiveCount());
        try {
            while (true) {
                if (startThreadPool.isTerminated()) {
                    log.info("批量启动实时任务线程执行中....../////......");
                    log.info("当前线程池活跃线程总数为2222：" + startThreadPool.getActiveCount());
                    break;
                }
                Thread.sleep(200);
            }
        } catch (Exception e) {
            log.error("停止批量启动实时任务线程出错：" + e.getMessage());
        }
        return Result.ok("文件上传中，请稍后");
    }

    /**
     * 测试直接上传文件到fastDfs
     *
     * @param file
     * @return
     */
    @Override
    public Result uploadToFastDfs(MultipartFile file, String suffix) {
        log.info("开始往fastDfs添加文件,fileName:" + file.getOriginalFilename() + ",date:" + DateUtil.formatDateToString(new Date()));
        long begin = java.lang.System.currentTimeMillis();
        String[] result = FastDFSClientUtil.uploadFile(trackerClient, file, suffix);
        long end = java.lang.System.currentTimeMillis();
        log.info("往fastDfs添加文件结束,fileName:" + file.getOriginalFilename() + ",共用时：" + (end - begin) / 1000D + "(秒),date:"
                + DateUtil.formatDateToString(new Date()) + ",result:" + JSON.toJSON(result));
        String filePath = httUrl + "/" + result[0] + "/" + result[1];
        log.info("上传到fastDfs文件存储位置：" + filePath);
        return Result.ok(filePath);
    }

    /**
     * 测试直接删除fastDfs文件
     *
     * @param group
     * @param path
     * @return
     */
    @Override
    public Result deleteFile(String group, String path) {
        int row = FastDFSClientUtil.deleteFile(group, path, trackerClient);
        log.info("删除文件，path:" + path + ",row:" + row);
        return Result.ok(row);
    }


    @Override
    public boolean checkFastFileIsExist(String urlStr) {
        if (StringUtils.isBlank(urlStr)) {
            return false;
        }
        boolean isExist = false;
        try {
            URL url = new URL(urlStr);
            URLConnection conn = url.openConnection();
            conn.getInputStream();
            log.info("获取url:" + urlStr + " ，文件成功，文件存在");
            isExist = true;
        } catch (Exception e) {
            // 获取失败
            ExceptionRes res = ExceptionResponseUtil.spliceMsgFromException(e);
            log.error("获取url:" + urlStr + "，文件失败：" + res.getMsg());
            isExist = false;
        }
        return isExist;
    }

}
