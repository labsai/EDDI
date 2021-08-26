import Modal from '@material-ui/core/Modal';
import * as _ from 'lodash';
import * as React from 'react';
import { useSelector } from 'react-redux';
import Slider from 'react-slick';
import 'slick-carousel/slick/slick-theme.css';
import 'slick-carousel/slick/slick.css';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { IAppState } from '../../../reducers';
import { pluginResourceSelector } from '../../../selectors/ModalSelectors';
import { packageSelector } from '../../../selectors/PackageSelectors';
import { pluginTempDataSelector } from '../../../selectors/PluginSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { isBotPage, isPackagePage } from '../../utils/helpers/getIdsFromPath';
import validateJson from '../../utils/helpers/ValidateJson';
import useModalStyles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';
import useStyles from './ParallelConfigModal.styles';
import PluginContainer from './PluginContainer';

interface IPublicProps {
  packageResource: string;
}

const ParallelConfigModal = ({ packageResource }: IPublicProps) => {
  const sliderRef = React.useRef(null);
  const pluginResource = useSelector(pluginResourceSelector);
  const [currentResource, setCurrentResource] = React.useState(pluginResource);
  const [alertOpened, setAlertOpened] = React.useState(false);
  const { packagePayload } = useSelector((state: IAppState) =>
    packageSelector(state, { packageResource }),
  );
  const plugins = [];

  const botOrPackagePage = isPackagePage() || isBotPage();

  packagePayload?.packageData?.packageExtensions?.forEach?.((p) => {
    const isParser = p.type.includes('parser');
    if (isParser) {
      plugins.push.apply(plugins, p.extensions.dictionaries);
      plugins.push.apply(plugins, p.extensions.corrections);
    } else {
      if (p.config?.uri) {
        plugins.push({ type: p.type, config: p.config });
      }
    }
  });
  const classes = useStyles();
  const modalClasses = useModalStyles();

  const filteredPlugins = plugins?.filter((p) => !!p?.config?.uri);

  const initialSlide =
    (filteredPlugins &&
      filteredPlugins?.findIndex?.((p) => p.config?.uri === pluginResource)) ||
    0;

  const settings = {
    dots: false,
    infinite: false,
    adaptiveHeight: true,
    speed: 500,
    arrows: false,
    swipe: false,
    slidesToShow: 1,
    slidesToScroll: 1,
    afterChange: (index: number) => {
      setCurrentResource(filteredPlugins[index]?.config?.uri);
    },
  };

  React.useEffect(() => {
    sliderRef?.current?.slickGoTo(initialSlide === -1 ? 0 : initialSlide);
  }, []);

  const handleNext = () => {
    sliderRef?.current?.slickNext();
  };
  const handlePrev = () => {
    sliderRef?.current?.slickPrev();
  };

  const pluginTempData = useSelector(pluginTempDataSelector);

  const packageHasChanges = !_.isEmpty(pluginTempData);

  const massUpdateJson = (deploy: boolean = false) => {
    Promise.all(
      pluginTempData.map((d) => {
        if (validateJson(d.schema, d.data)) {
          return d;
        } else {
          setAlertOpened(true);
          throw new Error('JSON is not valid');
        }
      }),
    )
      .then(() => {
        eddiApiActionDispatchers.massUpdateJsonDataAction(
          pluginTempData,
          deploy,
          currentResource,
        );
        if (deploy) {
          modalActionDispatchers.closeModal();
        }
      })
      .catch((e) => {
        console.log('Some errors in JSON: ', e);
      });
  };

  const closeAlert = () => {
    setAlertOpened(false);
  };

  return (
    <div className={classes.modalContainer}>
      <div className={classes.modalHeader}>
        <WhiteButton onClick={handlePrev} text={'Prev Config'} />
        <div className={classes.modalTopHeader}>
          {'Parallel config editing'}
        </div>
        <WhiteButton onClick={handleNext} text={'Next Config'} />
      </div>
      <div className={classes.actionButtons}>
        <BlueButton
          onClick={() => massUpdateJson()}
          text={'Save changes'}
          disabled={!packageHasChanges}
        />
        {botOrPackagePage && (
          <BlueButton
            onClick={() => massUpdateJson(true)}
            classes={{ button: classes.greenButton }}
            text={'Save & Run'}
            disabled={!packageHasChanges}
          />
        )}
      </div>
      <div className={classes.parallelConfigContainer}>
        {_.isEmpty(filteredPlugins) && (
          <div className={classes.empty}>All resources are empty</div>
        )}
        <Slider
          ref={sliderRef}
          {...settings}
          initialSlide={initialSlide === -1 ? 0 : initialSlide}>
          {filteredPlugins.map((p, i) => {
            return (
              <PluginContainer
                key={p.config?.uri + i}
                pluginResource={p.config?.uri}
                type={p.type}
                currentVersion={packagePayload.currentVersion}
                sliderRef={sliderRef}
              />
            );
          })}
        </Slider>
      </div>
      <Modal open={alertOpened} onClose={closeAlert}>
        <div className={modalClasses.paper}>
          <p>JSON is not valid</p>
          <div>
            <BlueButton onClick={closeAlert} text={'OK'} />
          </div>
        </div>
      </Modal>
    </div>
  );
};

export default ParallelConfigModal;
