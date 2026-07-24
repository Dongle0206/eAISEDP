package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.mapper.ArtifactMapper;
import com.eaiselp.data.service.ArtifactService;
import org.springframework.stereotype.Service;

@Service
public class ArtifactServiceImpl extends ServiceImpl<ArtifactMapper, Artifact> implements ArtifactService {
}
