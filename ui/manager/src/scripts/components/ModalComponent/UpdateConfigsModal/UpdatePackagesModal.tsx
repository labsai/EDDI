import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { packagesWithPluginSelector } from '../../../selectors/PackageSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { getPackagesUsingPlugin, IPackage } from '../../utils/AxiosFunctions';
import Parser from '../../utils/Parser';
import useStyles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import SelectableConfig from './SelectableConfig';

interface IPublicProps {
  pluginResource: string;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  packages: IPackage[];
}

const UpdatePackagesModal = ({ pluginResource }: IPrivateProps) => {
  const [selectedPackages, setSelectedPackages] = React.useState<string[]>([]);
  const [packages, setPackages] = React.useState<IPackage[]>(null);

  const classes = useStyles();

  React.useEffect(() => {
    loadPackagesUsingPlugin();
  }, []);

  const closeModal = () => {
    ModalActionDispatchers.closeModal();
  };

  const updateSelectedPackages = () => {
    eddiApiActionDispatchers.updatePackagesAction(
      pluginResource,
      selectedPackages,
    );
    closeModal();
  };

  const loadPackagesUsingPlugin = async () => {
    eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
      pluginResource,
      true,
    );
    const packages: IPackage[] = await getPackagesUsingPlugin(
      pluginResource,
      true,
    );
    setPackages(packages);
  };

  const selectPackage = (packageResource: string) => {
    if (selectedPackages.includes(packageResource)) {
      setSelectedPackages(
        selectedPackages.filter((pack) => pack !== packageResource),
      );
    } else {
      setSelectedPackages(selectedPackages.concat(packageResource));
    }
  };

  const isPackageSelected = (packageResource: string): boolean => {
    return !!selectedPackages.find(
      (selectedPackage) =>
        Parser.getId(packageResource) === Parser.getId(selectedPackage),
    );
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div
            className={
              classes.title
            }>{`Select packages to update any old versions of the extension to latest`}</div>
          <div className={classes.centerFlex} />
          <BlueButton
            classes={{ button: classes.button }}
            onClick={updateSelectedPackages}
            text={'Update selected'}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.centerFlex} />
          <div className={classes.lastModified}>{'Last modified'}</div>
        </div>
      </div>
      <div>
        {!_.isEmpty(packages) && (
          <div className={classes.packageList}>
            {packages.map((pack, i) => (
              <SelectableConfig
                key={i}
                selected={isPackageSelected(pack.resource)}
                descriptor={pack}
                handleClick={selectPackage}
              />
            ))}
          </div>
        )}
        {!packages && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!!packages && _.isEmpty(packages) && (
          <div>{'Found no packages that can be updated'}</div>
        )}
      </div>
    </div>
  );
};
const ComposedUpdatePackagesModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('UpdatePackagesModal'),
  connect(packagesWithPluginSelector),
)(UpdatePackagesModal);

export default ComposedUpdatePackagesModal;
