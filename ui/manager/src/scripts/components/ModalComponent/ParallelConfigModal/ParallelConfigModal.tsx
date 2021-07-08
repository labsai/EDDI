import * as React from 'react';
import Slider from 'react-slick';
import 'slick-carousel/slick/slick-theme.css';
import 'slick-carousel/slick/slick.css';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { IPackage } from '../../utils/AxiosFunctions';
import '../ModalComponent.styles.scss';
import useStyles from './ParallelConfigModal.styles';
import PluginContainer from './PluginContainer';
import * as _ from 'lodash';

interface IPublicProps {
  packagePayload: IPackage;
}

const ParallelConfigModal = ({ packagePayload }: IPublicProps) => {
  const sliderRef = React.useRef(null);
  const plugins = [];
  packagePayload.packageData.packageExtensions.forEach((p) => {
    const isParser = p.type.includes('parser');
    if (isParser) {
      plugins.push.apply(plugins, p.extensions.dictionaries);
    } else {
      if (p.config?.uri) {
        plugins.push({ type: p.type, config: p.config });
      }
    }
  });
  const classes = useStyles();

  const settings = {
    dots: false,
    infinite: false,
    adaptiveHeight: true,
    speed: 500,
    arrows: false,
    slidesToShow: 1,
    slidesToScroll: 1,
  };

  const handleNext = () => {
    sliderRef?.current?.slickNext();
  };
  const handlePrev = () => {
    sliderRef?.current?.slickPrev();
  };

  return (
    <form className={classes.modalContainer}>
      <div className={classes.modalHeader}>
        <WhiteButton onClick={handlePrev} text={'Prev Config'} />
        <div className={classes.modalTopHeader}>
          {'Parallel config editing'}
        </div>
        <WhiteButton onClick={handleNext} text={'Next Config'} />
      </div>
      <div className={classes.parallelConfigContainer}>
        {_.isEmpty(plugins) && (
          <div className={classes.empty}>All resources are empty</div>
        )}
        <Slider ref={sliderRef} {...settings}>
          {plugins.map((p, i) => {
            return (
              <PluginContainer
                key={p.config.uri + i}
                pluginResource={p.config.uri}
                type={p.type}
              />
            );
          })}
        </Slider>
      </div>
    </form>
  );
};

export default ParallelConfigModal;
